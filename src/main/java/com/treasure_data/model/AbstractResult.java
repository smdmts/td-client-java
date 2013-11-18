//
// Java Client Library for Treasure Data Cloud
//
// Copyright (C) 2011 - 2013 Muga Nishizawa
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//        http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//
package com.treasure_data.model;

public abstract class AbstractResult<T extends AbstractModel> implements Result<T> {

    private T model;
    protected int retryCount = 0;

    protected AbstractResult() {
        this(null);
    }

    protected AbstractResult(T model) {
        this.model = model;
    }

    protected T get() {
        return model;
    }

    protected void set(T model) {
        this.model = model;
    }

    public void incrRetryCount() {
        retryCount++;
    }

    public int getRetryCount() {
        return retryCount;
    }

}
