package org.zalando.stups.fullstop.jobs.ec2;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.conn.ssl.SSLContextBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.concurrent.ListenableFuture;
import org.zalando.stups.fullstop.aws.ClientProvider;
import org.zalando.stups.fullstop.jobs.FullstopJob;
import org.zalando.stups.fullstop.jobs.common.*;
import org.zalando.stups.fullstop.jobs.config.JobsProperties;
import org.zalando.stups.fullstop.violation.Violation;
import org.zalando.stups.fullstop.violation.ViolationBuilder;
import org.zalando.stups.fullstop.violation.ViolationSink;
import org.zalando.stups.fullstop.violation.service.ViolationService;

import javax.annotation.PostConstruct;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

import static com.amazonaws.regions.Region.getRegion;
import static com.amazonaws.regions.Regions.fromName;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static java.util.stream.Collectors.toList;
import static org.zalando.stups.fullstop.violation.ViolationType.UNSECURED_PUBLIC_ENDPOINT;

@Component
public class FetchEC2Job implements FullstopJob {

    private static final String EVENT_ID = "checkPublicEC2InstanceJob";

    private final Logger log = LoggerFactory.getLogger(FetchEC2Job.class);

    private final ViolationSink violationSink;

    private final ClientProvider clientProvider;

    private final AccountIdSupplier allAccountIds;

    private final JobsProperties jobsProperties;

    private final SecurityGroupsChecker securityGroupsChecker;

    private final ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();

    private final CloseableHttpClient httpclient;

    private final AwsApplications awsApplications;

    private final ViolationService violationService;

    @Autowired
    public FetchEC2Job(ViolationSink violationSink,
                       ClientProvider clientProvider,
                       AccountIdSupplier allAccountIds, final JobsProperties jobsProperties,
                       @Qualifier("ec2SecurityGroupsChecker") SecurityGroupsChecker securityGroupsChecker,
                       AwsApplications awsApplications,
                       ViolationService violationService) {
        this.violationSink = violationSink;
        this.clientProvider = clientProvider;
        this.allAccountIds = allAccountIds;
        this.jobsProperties = jobsProperties;
        this.securityGroupsChecker = securityGroupsChecker;
        this.awsApplications = awsApplications;
        this.violationService = violationService;

        threadPoolTaskExecutor.setCorePoolSize(12);
        threadPoolTaskExecutor.setMaxPoolSize(20);
        threadPoolTaskExecutor.setQueueCapacity(75);
        threadPoolTaskExecutor.setAllowCoreThreadTimeOut(true);
        threadPoolTaskExecutor.setKeepAliveSeconds(30);
        threadPoolTaskExecutor.setThreadGroupName("ec2-check-group");
        threadPoolTaskExecutor.setThreadNamePrefix("ec2-check-");
        threadPoolTaskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        threadPoolTaskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        threadPoolTaskExecutor.afterPropertiesSet();

        try {
            final RequestConfig config = RequestConfig.custom()
                    .setConnectionRequestTimeout(1000)
                    .setConnectTimeout(1000)
                    .setSocketTimeout(1000)
                    .build();
            httpclient = HttpClientBuilder.create()
                    .disableAuthCaching()
                    .disableAutomaticRetries()
                    .disableConnectionState()
                    .disableCookieManagement()
                    .disableRedirectHandling()
                    .setDefaultRequestConfig(config)
                    .setHostnameVerifier(new AllowAllHostnameVerifier())
                    .setSslcontext(
                            new SSLContextBuilder()
                                    .loadTrustMaterial(
                                            null,
                                            (arrayX509Certificate, value) -> true)
                                    .build())
                    .build();
        } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException e) {
            throw new IllegalStateException("Could not initialize httpClient", e);
        }
    }

    @PostConstruct
    public void init() {
        log.info("{} initalized", getClass().getSimpleName());
    }

    @Scheduled(fixedRate = 300_000, initialDelay = 240_000) // 5 min rate, 4 min delay
    public void run() {
        log.info("Running job {}", getClass().getSimpleName());
        for (String account : allAccountIds.get()) {
            for (String region : jobsProperties.getWhitelistedRegions()) {

                try {

                    log.info("Scanning public EC2 instances for {}/{}", account, region);

                    DescribeInstancesResult describeEC2Result = getDescribeEC2Result(
                            account,
                            region);

                    for (final Reservation reservation : describeEC2Result.getReservations()) {

                        for (final Instance instance : reservation.getInstances()) {
                            final Map<String, Object> metaData = newHashMap();
                            final List<String> errorMessages = newArrayList();
                            final String instancePublicIpAddress = instance.getPublicIpAddress();

                            if (violationService.violationExists(account, region, EVENT_ID, instance.getInstanceId(), UNSECURED_PUBLIC_ENDPOINT)) {
                                continue;
                            }

                            final Set<String> unsecureGroups = securityGroupsChecker.check(
                                    instance.getSecurityGroups().stream().map(GroupIdentifier::getGroupId).collect(toList()),
                                    account,
                                    getRegion(fromName(region)));
                            if (!unsecureGroups.isEmpty()) {
                                metaData.put("unsecuredSecurityGroups", unsecureGroups);
                                errorMessages.add("Unsecured security group! Only ports 80 and 443 are allowed");
                            }

                            if (metaData.size() > 0) {
                                metaData.put("errorMessages", errorMessages);
                                writeViolation(account, region, metaData, instance.getInstanceId());

                                // skip http response check, as we are already having a violation here
                                continue;
                            }

                            // skip check for publicly available apps
                            if (awsApplications.isPubliclyAccessible(account, region, newArrayList(instance.getInstanceId())).orElse(false)) {
                                continue;
                            }

                            for (Integer allowedPort : jobsProperties.getEc2AllowedPorts()) {

                                if (allowedPort == 22) {
                                    continue;
                                }

                                HttpGetRootCall httpCall = new HttpGetRootCall(httpclient, instancePublicIpAddress, allowedPort);
                                ListenableFuture<HttpCallResult> listenableFuture = threadPoolTaskExecutor.submitListenable(
                                        httpCall);
                                listenableFuture.addCallback(
                                        httpCallResult -> {
                                            log.info("address: {} and port: {}", instancePublicIpAddress, allowedPort);
                                            if (httpCallResult.isOpen()) {
                                                Map<String, Object> md = newHashMap();
                                                md.put("instancePublicIpAddress", instancePublicIpAddress);
                                                md.put("Port", allowedPort);
                                                md.put("Error", httpCallResult.getMessage());
                                                writeViolation(account, region, md, instance.getInstanceId());
                                            }
                                        }, ex -> log.warn("Could not call " + instancePublicIpAddress, ex));

                                log.debug("Active threads in pool: {}/{}", threadPoolTaskExecutor.getActiveCount(), threadPoolTaskExecutor.getMaxPoolSize());
                            }

                        }

                    }

                } catch (AmazonServiceException a) {

                    if (a.getErrorCode().equals("RequestLimitExceeded")) {
                        log.warn("RequestLimitExceeded for account: {}", account);
                    } else {
                        log.error(a.getMessage(), a);
                    }

                }
            }
        }
    }

    private void writeViolation(String account, String region, Object metaInfo, String instanceId) {
        ViolationBuilder violationBuilder = new ViolationBuilder();
        Violation violation = violationBuilder.withAccountId(account)
                .withRegion(region)
                .withPluginFullyQualifiedClassName(FetchEC2Job.class)
                .withType(UNSECURED_PUBLIC_ENDPOINT)
                .withMetaInfo(metaInfo)
                .withInstanceId(instanceId)
                .withEventId(EVENT_ID).build();
        violationSink.put(violation);
    }

    private DescribeInstancesResult getDescribeEC2Result(String account, String region) {
        AmazonEC2Client ec2Client = clientProvider.getClient(
                AmazonEC2Client.class,
                account,
                getRegion(
                        fromName(region)));
        DescribeInstancesRequest describeInstancesRequest = new DescribeInstancesRequest();
        describeInstancesRequest.setFilters(newArrayList(new Filter("ip-address", newArrayList("*"))));
        return ec2Client.describeInstances(describeInstancesRequest);
    }
}
