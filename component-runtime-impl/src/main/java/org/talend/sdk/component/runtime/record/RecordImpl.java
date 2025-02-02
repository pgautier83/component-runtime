/**
 * Copyright (C) 2006-2021 Talend Inc. - www.talend.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.talend.sdk.component.runtime.record;

import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;
import static org.talend.sdk.component.api.record.Schema.Type.ARRAY;
import static org.talend.sdk.component.api.record.Schema.Type.BOOLEAN;
import static org.talend.sdk.component.api.record.Schema.Type.BYTES;
import static org.talend.sdk.component.api.record.Schema.Type.DATETIME;
import static org.talend.sdk.component.api.record.Schema.Type.DOUBLE;
import static org.talend.sdk.component.api.record.Schema.Type.FLOAT;
import static org.talend.sdk.component.api.record.Schema.Type.INT;
import static org.talend.sdk.component.api.record.Schema.Type.LONG;
import static org.talend.sdk.component.api.record.Schema.Type.RECORD;
import static org.talend.sdk.component.api.record.Schema.Type.STRING;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbConfig;
import javax.json.bind.annotation.JsonbTransient;
import javax.json.bind.config.PropertyOrderStrategy;
import javax.json.spi.JsonProvider;

import org.talend.sdk.component.api.record.Record;
import org.talend.sdk.component.api.record.Schema;
import org.talend.sdk.component.api.record.Schema.Entry;

import lombok.Getter;

public final class RecordImpl implements Record {

    private static final RecordConverters RECORD_CONVERTERS = new RecordConverters();

    private final Map<String, Object> values;

    @Getter
    @JsonbTransient
    private final Schema schema;

    private RecordImpl(final Map<String, Object> values, final Schema schema) {
        this.values = values;
        this.schema = schema;
    }

    @Override
    public <T> T get(final Class<T> expectedType, final String name) {
        final Object value = values.get(name);
        if (value == null || expectedType.isInstance(value)) {
            return expectedType.cast(value);
        }

        return RECORD_CONVERTERS.coerce(expectedType, value, name);
    }

    @Override // for debug purposes, don't use it for anything else
    public String toString() {
        try (final Jsonb jsonb = JsonbBuilder
                .create(new JsonbConfig()
                        .withFormatting(true)
                        .withPropertyOrderStrategy(PropertyOrderStrategy.LEXICOGRAPHICAL)
                        .setProperty("johnzon.cdi.activated", false))) {
            return new RecordConverters()
                    .toType(new RecordConverters.MappingMetaRegistry(), this, JsonObject.class,
                            () -> Json.createBuilderFactory(emptyMap()), JsonProvider::provider, () -> jsonb,
                            () -> new RecordBuilderFactoryImpl("tostring"))
                    .toString();
        } catch (final Exception e) {
            return super.toString();
        }
    }

    // Entry creation can be optimized a bit but recent GC should not see it as a big deal
    public static class BuilderImpl implements Builder {

        private final Map<String, Object> values = new HashMap<>(8);

        private final List<Schema.Entry> entries = new ArrayList<>(8);

        private final Schema providedSchema;

        private Map<String, Schema.Entry> entryIndex;

        public BuilderImpl() {
            this(null);
        }

        public BuilderImpl(final Schema providedSchema) {
            this.providedSchema = providedSchema;
        }

        @Override
        public Object getValue(final String name) {
            return this.values.get(name);
        }

        @Override
        public Builder with(final Entry entry, final Object value) {
            validateTypeAgainstProvidedSchema(entry.getName(), entry.getType(), value);
            if (!entry.getType().isCompatible(value)) {
                throw new IllegalArgumentException(String
                        .format("Entry '%s' of type %s is not compatible with value of type '%s'", entry.getName(),
                                entry.getType(), value.getClass().getName()));
            }
            if (entry.getType() == Schema.Type.DATETIME) {
                if (value == null) {
                    return this;
                } else if (value instanceof Long) {
                    this.withTimestamp(entry, (Long) value);
                } else if (value instanceof Date) {
                    this.withDateTime(entry, (Date) value);
                } else if (value instanceof ZonedDateTime) {
                    this.withDateTime(entry, (ZonedDateTime) value);
                } else if (value instanceof Temporal) {
                    this.withTimestamp(entry, ((Temporal) value).get(ChronoField.INSTANT_SECONDS) * 1000L);
                }
                return this;
            } else {
                return append(entry, value);
            }
        }

        private Schema.Entry findExistingEntry(final String name) {
            if (this.entryIndex == null) {
                this.entryIndex =
                        providedSchema.getEntries().stream().collect(toMap(Schema.Entry::getName, identity()));
            }
            final Schema.Entry entry = this.entryIndex.get(name);
            if (entry == null) {
                throw new IllegalArgumentException(
                        "No entry '" + name + "' expected in provided schema: " + entryIndex.keySet());
            }
            return entry;
        }

        private Schema.Entry findOrBuildEntry(final String name, final Schema.Type type, final boolean nullable) {
            if (providedSchema == null) {
                return new SchemaImpl.EntryImpl.BuilderImpl()
                        .withName(name)
                        .withType(type)
                        .withNullable(nullable)
                        .build();
            }
            return this.findExistingEntry(name);
        }

        private Schema.Entry validateTypeAgainstProvidedSchema(final String name, final Schema.Type type,
                final Object value) {
            if (providedSchema == null) {
                return null;
            }

            final Schema.Entry entry = this.findExistingEntry(name);
            if (entry.getType() != type) {
                throw new IllegalArgumentException(
                        "Entry '" + name + "' expected to be a " + entry.getType() + ", got a " + type);
            }
            if (value == null && !entry.isNullable()) {
                throw new IllegalArgumentException("Entry '" + name + "' is not nullable");
            }
            return entry;
        }

        public Record build() {
            if (providedSchema != null) {
                final String missing = providedSchema
                        .getEntries()
                        .stream()
                        .filter(it -> !it.isNullable() && !values.containsKey(it.getName()))
                        .map(Schema.Entry::getName)
                        .collect(joining(", "));
                if (!missing.isEmpty()) {
                    throw new IllegalArgumentException("Missing entries: " + missing);
                }
            }
            return new RecordImpl(unmodifiableMap(values),
                    providedSchema == null ? new SchemaImpl(RECORD, null, unmodifiableList(entries)) : providedSchema);
        }

        // here the game is to add an entry method for each kind of type + its companion with Entry provider

        public Builder withString(final String name, final String value) {
            final Schema.Entry entry = this.findOrBuildEntry(name, STRING, true);
            return withString(entry, value);
        }

        public Builder withString(final Schema.Entry entry, final String value) {
            assertType(entry.getType(), STRING);
            validateTypeAgainstProvidedSchema(entry.getName(), STRING, value);
            return append(entry, value);
        }

        public Builder withBytes(final String name, final byte[] value) {
            final Schema.Entry entry = this.findOrBuildEntry(name, BYTES, true);
            return withBytes(entry, value);
        }

        public Builder withBytes(final Schema.Entry entry, final byte[] value) {
            assertType(entry.getType(), BYTES);
            validateTypeAgainstProvidedSchema(entry.getName(), BYTES, value);
            return append(entry, value);
        }

        public Builder withDateTime(final String name, final Date value) {
            final Schema.Entry entry = this.findOrBuildEntry(name, DATETIME, true);
            return withDateTime(entry, value);
        }

        public Builder withDateTime(final Schema.Entry entry, final Date value) {
            if (value == null && !entry.isNullable()) {
                throw new IllegalArgumentException("date '" + entry.getName() + "' is not allowed to be null");
            }
            validateTypeAgainstProvidedSchema(entry.getName(), DATETIME, value);
            return withTimestamp(entry, value == null ? -1 : value.getTime());
        }

        public Builder withDateTime(final String name, final ZonedDateTime value) {
            final Schema.Entry entry = this.findOrBuildEntry(name, DATETIME, true);
            return withDateTime(entry, value);
        }

        public Builder withDateTime(final Schema.Entry entry, final ZonedDateTime value) {
            if (value == null && !entry.isNullable()) {
                throw new IllegalArgumentException("datetime '" + entry.getName() + "' is not allowed to be null");
            }
            validateTypeAgainstProvidedSchema(entry.getName(), DATETIME, value);
            return withTimestamp(entry, value == null ? -1 : value.toInstant().toEpochMilli());
        }

        public Builder withTimestamp(final String name, final long value) {
            final Schema.Entry entry = this.findOrBuildEntry(name, DATETIME, false);
            return withTimestamp(entry, value);
        }

        public Builder withTimestamp(final Schema.Entry entry, final long value) {
            assertType(entry.getType(), DATETIME);
            validateTypeAgainstProvidedSchema(entry.getName(), DATETIME, value);
            return append(entry, value);
        }

        public Builder withInt(final String name, final int value) {
            final Schema.Entry entry = this.findOrBuildEntry(name, INT, false);
            return withInt(entry, value);
        }

        public Builder withInt(final Schema.Entry entry, final int value) {
            assertType(entry.getType(), INT);
            validateTypeAgainstProvidedSchema(entry.getName(), INT, value);
            return append(entry, value);
        }

        public Builder withLong(final String name, final long value) {
            final Schema.Entry entry = this.findOrBuildEntry(name, LONG, false);
            return withLong(entry, value);
        }

        public Builder withLong(final Schema.Entry entry, final long value) {
            assertType(entry.getType(), LONG);
            validateTypeAgainstProvidedSchema(entry.getName(), LONG, value);
            return append(entry, value);
        }

        public Builder withFloat(final String name, final float value) {
            final Schema.Entry entry = this.findOrBuildEntry(name, FLOAT, false);
            return withFloat(entry, value);
        }

        public Builder withFloat(final Schema.Entry entry, final float value) {
            assertType(entry.getType(), FLOAT);
            validateTypeAgainstProvidedSchema(entry.getName(), FLOAT, value);
            return append(entry, value);
        }

        public Builder withDouble(final String name, final double value) {
            final Schema.Entry entry = this.findOrBuildEntry(name, DOUBLE, false);
            return withDouble(entry, value);
        }

        public Builder withDouble(final Schema.Entry entry, final double value) {
            assertType(entry.getType(), DOUBLE);
            validateTypeAgainstProvidedSchema(entry.getName(), DOUBLE, value);
            return append(entry, value);
        }

        public Builder withBoolean(final String name, final boolean value) {
            final Schema.Entry entry = this.findOrBuildEntry(name, BOOLEAN, false);
            return withBoolean(entry, value);
        }

        public Builder withBoolean(final Schema.Entry entry, final boolean value) {
            assertType(entry.getType(), BOOLEAN);
            validateTypeAgainstProvidedSchema(entry.getName(), BOOLEAN, value);
            return append(entry, value);
        }

        public Builder withRecord(final Schema.Entry entry, final Record value) {
            assertType(entry.getType(), RECORD);
            if (entry.getElementSchema() == null) {
                throw new IllegalArgumentException("No schema for the nested record");
            }
            validateTypeAgainstProvidedSchema(entry.getName(), RECORD, value);
            return append(entry, value);
        }

        public Builder withRecord(final String name, final Record value) {
            return withRecord(new SchemaImpl.EntryImpl.BuilderImpl()
                    .withName(name)
                    .withElementSchema(value.getSchema())
                    .withType(RECORD)
                    .withNullable(true)
                    .build(), value);
        }

        public <T> Builder withArray(final Schema.Entry entry, final Collection<T> values) {
            assertType(entry.getType(), ARRAY);
            if (entry.getElementSchema() == null) {
                throw new IllegalArgumentException("No schema for the collection items");
            }
            validateTypeAgainstProvidedSchema(entry.getName(), ARRAY, values);
            // todo: check item type?
            return append(entry, values);
        }

        private void assertType(final Schema.Type actual, final Schema.Type expected) {
            if (actual != expected) {
                throw new IllegalArgumentException("Expected entry type: " + expected + ", got: " + actual);
            }
        }

        private <T> Builder append(final Schema.Entry entry, final T value) {
            if (value != null) {
                values.put(entry.getName(), value);
            } else if (!entry.isNullable()) {
                throw new IllegalArgumentException(entry.getName() + " is not nullable but got a null value");
            }
            if (providedSchema == null) {
                entries.add(entry);
            }
            return this;
        }
    }
}
