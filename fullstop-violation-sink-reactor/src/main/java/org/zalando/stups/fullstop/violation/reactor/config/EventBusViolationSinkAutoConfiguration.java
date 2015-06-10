/**
 * Copyright (C) 2015 Zalando SE (http://tech.zalando.com)
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zalando.stups.fullstop.violation.reactor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.stups.fullstop.violation.reactor.EventBusViolationSink;
import reactor.Environment;
import reactor.bus.EventBus;

/**
 * @author jbellmann
 */
@Configuration
public class EventBusViolationSinkAutoConfiguration {

    @Bean
    public EventBusViolationSink eventBusViolationSink() {
        return new EventBusViolationSink(eventBus());
    }

    @Bean
    public EventBus eventBus() {
        Environment.initialize();

        return EventBus.create(Environment.get());
    }
}