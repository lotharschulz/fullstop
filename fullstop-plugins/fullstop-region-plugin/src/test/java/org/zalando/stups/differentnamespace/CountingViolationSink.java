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
package org.zalando.stups.differentnamespace;

import org.zalando.stups.fullstop.violation.Violation;
import org.zalando.stups.fullstop.violation.ViolationSink;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author jbellmann
 */
public class CountingViolationSink implements ViolationSink {

    private AtomicInteger counter = new AtomicInteger();

    @Override
    public void put(final Violation violation) {
        counter.incrementAndGet();
    }

    public int getInvocationCount() {
        return counter.get();
    }
}