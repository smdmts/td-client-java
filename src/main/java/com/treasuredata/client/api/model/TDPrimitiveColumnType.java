/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.treasuredata.client.api.model;

public enum TDPrimitiveColumnType
        implements TDColumnType
{
    INT("int"),
    LONG("long"),
    FLOAT("float"),
    DOUBLE("double"),
    BOOLEAN("boolean"),
    STRING("string");
    private String name;

    private TDPrimitiveColumnType(String name)
    {
        this.name = name;
    }

    @Override
    public String toString()
    {
        return name;
    }

    @Override
    public boolean isPrimitive()
    {
        return true;
    }

    @Override
    public boolean isArrayType()
    {
        return false;
    }

    @Override
    public boolean isMapType()
    {
        return false;
    }

    @Override
    public TDPrimitiveColumnType asPrimitiveType()
    {
        return this;
    }

    @Override
    public TDArrayColumnType asArrayType()
    {
        return null;
    }

    @Override
    public TDMapColumnType asMapType()
    {
        return null;
    }
}
