/* 
 Copyright (C) GridGain Systems. All Rights Reserved.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

/*  _________        _____ __________________        _____
*  __  ____/___________(_)______  /__  ____/______ ____(_)_______
*  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
*  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
*  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
*/

package org.gridgain.grid.kernal.processors.cache.query;

import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.io.*;

/**
 * Immutable query metrics key used to group metrics.
 */
class GridCacheQueryMetricsKey implements Externalizable {
    /** */
    private GridCacheQueryType type;

    /** */
    private Class<?> cls;

    /** */
    private String clause;

    /**
     * Constructs key.
     *
     * @param type Query type.
     * @param cls Query return type.
     * @param clause Query clause.
     */
    GridCacheQueryMetricsKey(@Nullable GridCacheQueryType type,
        @Nullable Class<?> cls, @Nullable String clause) {
        this.type = type;
        this.cls = cls;
        this.clause = clause;
    }

    /**
     * Required by {@link Externalizable}.
     */
    public GridCacheQueryMetricsKey() {
        // No-op.
    }

    /**
     * @return Query type.
     */
    GridCacheQueryType type() {
        return type;
    }

    /**
     * @return Query return type.
     */
    Class<?> queryClass() {
        return cls;
    }

    /**
     * @return Query clause.
     */
    String clause() {
        return clause;
    }

    /** {@inheritDoc} */
    @Override public boolean equals(Object obj) {
        if (obj == this)
            return true;

        if (!(obj instanceof GridCacheQueryMetricsKey))
            return false;

        GridCacheQueryMetricsKey oth = (GridCacheQueryMetricsKey)obj;

        return oth.type() == type && F.eq(oth.queryClass(), cls) && F.eq(oth.clause(), clause);
    }

    /** {@inheritDoc} */
    @Override public int hashCode() {
        return (type != null ? type.ordinal() : -1) +
            31 * (cls != null ? cls.hashCode() : 0) +
            31 * 31 * (clause != null ? clause.hashCode() : 0);
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        out.writeByte(type != null ? type.ordinal() : -1);
        out.writeObject(cls);
        U.writeString(out, clause);
    }

    /** {@inheritDoc} */
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        byte ord = in.readByte();

        type = ord >= 0 ? GridCacheQueryType.fromOrdinal(ord) : null;
        cls = (Class<?>)in.readObject();
        clause = U.readString(in);
    }
}
