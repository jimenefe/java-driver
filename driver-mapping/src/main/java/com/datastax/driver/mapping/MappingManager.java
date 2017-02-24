/*
 *      Copyright (C) 2012-2015 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.mapping;

import com.datastax.driver.core.*;
import com.datastax.driver.mapping.annotations.Accessor;
import com.datastax.driver.mapping.annotations.Table;
import com.datastax.driver.mapping.annotations.UDT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mapping manager from which to obtain entity mappers.
 */
public class MappingManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(MappingManager.class);

    private final Session session;
    private final MappingConfiguration defaultConfiguration;
    final boolean isCassandraV1;

    private final ConcurrentHashMap<Class<?>, Mapper<?>> mappers = new ConcurrentHashMap<Class<?>, Mapper<?>>();
    private final ConcurrentHashMap<Class<?>, MappedUDTCodec<?>> udtCodecs = new ConcurrentHashMap<Class<?>, MappedUDTCodec<?>>();
    private final ConcurrentHashMap<Class<?>, Object> accessors = new ConcurrentHashMap<Class<?>, Object>();


    /**
     * Creates a new {@code MappingManager} using the provided {@code Session} with default
     * {@code MapperConfiguration}.
     * <p/>
     * Note that this constructor forces the initialization of the session (see
     * {@link #MappingManager(Session, ProtocolVersion)} if that is a problem for you).
     *
     * @param session the {@code Session} to use.
     */
    public MappingManager(Session session) {
        this(session, getProtocolVersion(session));
    }

    private static ProtocolVersion getProtocolVersion(Session session) {
        session.init();
        return session.getCluster().getConfiguration().getProtocolOptions().getProtocolVersion();
    }

    /**
     * Creates a new {@code MappingManager} using the provided {@code Session} with default
     * {@code MapperConfiguration}.
     * <p/>
     * This constructor is only provided for backward compatibility: before 2.1.7, {@code MappingManager} could be
     * built from an uninitialized session; since 2.1.7, the mapper needs to know the active protocol version to
     * adapt its internal requests, so {@link #MappingManager(Session)} will now initialize the session if needed.
     * If you rely on the session not being initialized, use this constructor and provide the version manually.
     *
     * @param session         the {@code Session} to use.
     * @param protocolVersion the protocol version that will be used with this session.
     * @since 2.1.7
     */
    public MappingManager(Session session, ProtocolVersion protocolVersion) {
        this(session, new MappingConfiguration(), protocolVersion);
    }

    /**
     * Creates a new {@code MappingManager} using the provided {@code Session} with custom configuration
     * to be inherited to each instantiated mapper.
     * <p/>
     * Note that this constructor forces the initialization of the session (see
     * {@link #MappingManager(Session, ProtocolVersion)} if that is a problem for you).
     *
     * @param session       the {@code Session} to use.
     * @param configuration the {@code MapperConfiguration} to use be used as default for instantiated mappers.
     */
    public MappingManager(Session session, MappingConfiguration configuration) {
        this(session, configuration, getProtocolVersion(session));
    }

    /**
     * Creates a new {@code MappingManager} using the provided {@code Session} with default
     * {@code MapperConfiguration}.
     * <p/>
     * This constructor is only provided for backward compatibility: before 2.1.7, {@code MappingManager} could be
     * built from an uninitialized session; since 2.1.7, the mapper needs to know the active protocol version to
     * adapt its internal requests, so {@link #MappingManager(Session)} will now initialize the session if needed.
     * If you rely on the session not being initialized, use this constructor and provide the version manually.
     *
     * @param session         the {@code Session} to use.
     * @param configuration   the {@code MapperConfiguration} to use be used as default for instantiated mappers.
     * @param protocolVersion the protocol version that will be used with this session.
     */
    public MappingManager(Session session, MappingConfiguration configuration, ProtocolVersion protocolVersion) {
        this.session = session;
        this.defaultConfiguration = configuration;
        // This is not strictly correct because we could connect to C* 2.0 with the v1 protocol.
        // But mappers need to make a decision early so that generated queries are compatible, and we don't know in advance
        // which nodes might join the cluster later.
        // At least if protocol >=2 we know there won't be any 1.2 nodes ever.
        this.isCassandraV1 = (protocolVersion == ProtocolVersion.V1);
        session.getCluster().register(new SchemaChangeListenerBase() {

            @Override
            public void onTableRemoved(TableMetadata table) {
                synchronized (mappers) {
                    Iterator<Mapper<?>> it = mappers.values().iterator();
                    while (it.hasNext()) {
                        Mapper<?> mapper = it.next();
                        if (mapper.getTableMetadata().equals(table)) {
                            LOGGER.error("Table {} has been removed; existing mappers for @Entity annotated {} will not work anymore", table.getName(), mapper.getMappedClass());
                            it.remove();
                        }
                    }
                }
            }

            @Override
            public void onTableChanged(TableMetadata current, TableMetadata previous) {
                synchronized (mappers) {
                    Iterator<Mapper<?>> it = mappers.values().iterator();
                    while (it.hasNext()) {
                        Mapper<?> mapper = it.next();
                        if (mapper.getTableMetadata().equals(previous)) {
                            LOGGER.warn("Table {} has been altered; existing mappers for @Entity annotated {} might not work properly anymore",
                                    previous.getName(), mapper.getMappedClass());
                            it.remove();
                        }
                    }
                }
            }

            @Override
            public void onUserTypeRemoved(UserType type) {
                synchronized (udtCodecs) {
                    Iterator<MappedUDTCodec<?>> it = udtCodecs.values().iterator();
                    while (it.hasNext()) {
                        MappedUDTCodec<?> codec = it.next();
                        if (type.equals(codec.getCqlType())) {
                            LOGGER.error("User type {} has been removed; existing mappers for @UDT annotated {} will not work anymore",
                                    type, codec.getUdtClass());
                            it.remove();
                        }
                    }
                }
            }

            @Override
            public void onUserTypeChanged(UserType current, UserType previous) {
                synchronized (udtCodecs) {
                    Set<MappedUDTCodec<?>> deletedCodecs = new HashSet<MappedUDTCodec<?>>();
                    Iterator<MappedUDTCodec<?>> it = udtCodecs.values().iterator();
                    while (it.hasNext()) {
                        MappedUDTCodec<?> codec = it.next();
                        if (previous.equals(codec.getCqlType())) {
                            LOGGER.warn("User type {} has been altered; existing mappers for @UDT annotated {} might not work properly anymore",
                                    previous, codec.getUdtClass());
                            deletedCodecs.add(codec);
                            it.remove();
                        }
                    }
                    for (MappedUDTCodec<?> deletedCodec : deletedCodecs) {
                        // try to register an updated version of the previous codec
                        try {
                            getUDTCodec(deletedCodec.getUdtClass(), deletedCodec.getConfiguration());
                        } catch (Exception e) {
                            LOGGER.error("Could not update mapping for @UDT annotated " + deletedCodec.getUdtClass(), e);
                        }
                    }
                }
            }

        });
    }

    /**
     * The underlying {@code Session} used by this manager.
     * <p/>
     * Note that you can get obtain the {@code Cluster} object corresponding
     * to that session using {@code getSession().getCluster()}.
     * <p/>
     * It is inadvisable to close the returned Session while this manager and
     * its mappers are in use.
     *
     * @return the underlying session used by this manager.
     */
    public Session getSession() {
        return session;
    }

    /**
     * Creates a {@code Mapper} for the provided class (that must be annotated by a
     * {@link Table} annotation) with this manager's {@code MapperConfiguration}.
     * <p/>
     * The {@code MappingManager} only ever keeps one Mapper for each class, and so calling this
     * method multiple times on the same class will always return the same object.
     * Note that the returned mapper's configuration will be determined by the way it
     * was first created: if it was through this method, it will be the manager's default configuration,
     * otherwise if it was through {@link #mapper(Class, MappingConfiguration)}, it will be the configuration
     * that was passed to that call.
     * <p/>
     * If the type of any field in the class is an {@link UDT}-annotated classes, a codec for that
     * class will automatically be created and registered with the underlying {@code Cluster}.
     * This works recursively with UDTs nested in other UDTs or in collections.
     *
     * @param <T>   the type of the class to map.
     * @param klass the (annotated) class for which to return the mapper.
     * @return the {@code Mapper} object for class {@code klass}.
     */
    public <T> Mapper<T> mapper(Class<T> klass) {
        return getMapper(klass, defaultConfiguration);
    }

    /**
     * Creates a {@code Mapper} for the provided class (that must be annotated by a
     * {@link Table} annotation) with the given {@code MapperConfiguration}.
     * <p/>
     * The {@code MappingManager} only ever keeps one Mapper for each class, and so calling this
     * method multiple times on the same class will always return the same object.
     * Note that the returned mapper's configuration will be determined by the way it
     * was first created: if it was through this method, it will be the configuration that was passed to
     * that call, otherwise if it was through {@link #mapper(Class)}, it will be the manager's default
     * configuration.
     * <p/>
     * If the type of any field in the class is an {@link UDT}-annotated classes, a codec for that
     * class will automatically be created and registered with the underlying {@code Cluster}.
     * This works recursively with UDTs nested in other UDTs or in collections.
     *
     * @param <T>           the type of the class to map.
     * @param klass         the (annotated) class for which to return the mapper.
     * @param configuration the configuration to be loaded to the mapper (might be ignored if the mapper was already
     *                      created with another configuration).
     * @return the {@code Mapper} object for class {@code klass}.
     */
    public <T> Mapper<T> mapper(Class<T> klass, MappingConfiguration configuration) {
        return getMapper(klass, configuration);
    }

    /**
     * Creates a {@code TypeCodec} for the provided class (that must be annotated by
     * a {@link UDT} annotation) with this manager's {@code MapperConfiguration}.
     * <p/>
     * This method also registers the codec against the underlying {@code Cluster}.
     * In addition, the codecs for any nested UDTs will also be created and registered.
     * <p/>
     * You don't need to call this method explicitly if you already call {@link #mapper(Class)}
     * for a class that references this UDT class (creating a mapper will automatically
     * process all UDTs that it uses).
     * <p/>
     * The {@code MappingManager} only ever keeps one codec for each class; calling this method
     * multiple times on the same class will return the same object, except if the UDT schema is
     * altered at runtime: in that case, the codec will be replaced automatically.
     * Note that the returned codec's configuration will be determined by the way it
     * was first created: if it was through this method, it will be the manager's default configuration,
     * otherwise if it was through {@link #udtCodec(Class, MappingConfiguration)}, it will be the configuration
     * that was passed to that call.
     *
     * @param <T>   the type of the class to map.
     * @param klass the (annotated) class for which to return the codec.
     * @return the codec that maps the provided class to the corresponding user-defined type.
     */
    public <T> TypeCodec<T> udtCodec(Class<T> klass) {
        return getUDTCodec(klass, defaultConfiguration);
    }

    /**
     * Creates a {@code TypeCodec} for the provided class (that must be annotated by
     * a {@link UDT} annotation) with the given {@code MapperConfiguration}.
     * <p/>
     * This method also registers the codec against the underlying {@code Cluster}.
     * In addition, the codecs for any nested UDTs will also be created and registered.
     * <p/>
     * You don't need to call this method explicitly if you already call {@link #mapper(Class)}
     * for a class that references this UDT class (creating a mapper will automatically
     * process all UDTs that it uses).
     * <p/>
     * The {@code MappingManager} only ever keeps one codec for each class; calling this method
     * multiple times on the same class will return the same object, except if the UDT schema is
     * altered at runtime: in that case, the codec will be replaced automatically.
     * Note that the returned codec's configuration will be determined by the way it
     * was first created: if it was through this method, it will be the configuration
     * that was passed to that call, otherwise if it was through {@link #udtCodec(Class, MappingConfiguration)},
     * it will be the manager's default configuration.
     *
     * @param <T>           the type of the class to map.
     * @param klass         the (annotated) class for which to return the codec.
     * @param configuration the configuration to be used.
     * @return the codec that maps the provided class to the corresponding user-defined type.
     */
    public <T> TypeCodec<T> udtCodec(Class<T> klass, MappingConfiguration configuration) {
        return getUDTCodec(klass, configuration);
    }

    /**
     * Creates an accessor object based on the provided interface (that must be annotated by
     * a {@link Accessor} annotation) with this manager's {@code MapperConfiguration}.
     * <p/>
     * The {@code MappingManager} only ever keep one Accessor for each class, and so calling this
     * method multiple times on the same class will always return the same object.
     * Note that the returned accessor's configuration will be determined by the way it
     * was first created: if it was through this method, it will be the manager's default configuration,
     * otherwise if it was through {@link #createAccessor(Class, MappingConfiguration)}, it will be the configuration
     * that was passed to that call.
     *
     * @param <T>   the type of the accessor class.
     * @param klass the (annotated) class for which to create an accessor object.
     * @return the accessor object for class {@code klass}.
     */
    public <T> T createAccessor(Class<T> klass) {
        return getAccessor(klass, defaultConfiguration);
    }

    /**
     * Creates an accessor object based on the provided interface (that must be annotated by
     * a {@link Accessor} annotation) with the given {@code MapperConfiguration}.
     * <p/>
     * The {@code MappingManager} only ever keep one Accessor for each class, and so calling this
     * method multiple times on the same class will always return the same object.
     * Note that the returned accessor's configuration will be determined by the way it
     * was first created: if it was through this method, it will be the configuration that was passed to
     * that call, otherwise if it was through {@link #createAccessor(Class)}, it will be the manager's default
     * configuration.
     *
     * @param <T>           the type of the accessor class.
     * @param klass         the (annotated) class for which to create an accessor object.
     * @param configuration the configuration to be used.
     * @return the accessor object for class {@code klass}.
     */
    public <T> T createAccessor(Class<T> klass, MappingConfiguration configuration) {
        return getAccessor(klass, configuration);
    }

    @SuppressWarnings("unchecked")
    private <T> Mapper<T> getMapper(Class<T> klass, MappingConfiguration configuration) {
        configuration = new MappingConfiguration(configuration);
        Mapper<T> mapper = (Mapper<T>) mappers.get(klass);
        if (mapper == null) {
            EntityMapper<T> entityMapper = AnnotationParser.parseEntity(klass, this, configuration);
            mapper = new Mapper<T>(this, klass, entityMapper);
            Mapper<T> old = (Mapper<T>) mappers.putIfAbsent(klass, mapper);
            if (old != null) {
                mapper = old;
            }
        }
        return mapper;
    }

    @SuppressWarnings("unchecked")
    <T> TypeCodec<T> getUDTCodec(Class<T> mappedClass, MappingConfiguration configuration) {
        configuration = new MappingConfiguration(configuration);
        MappedUDTCodec<T> codec = (MappedUDTCodec<T>) udtCodecs.get(mappedClass);
        if (codec == null) {
            codec = AnnotationParser.parseUDT(mappedClass, this, configuration);
            session.getCluster().getConfiguration().getCodecRegistry().register(codec);
            MappedUDTCodec<T> old = (MappedUDTCodec<T>) udtCodecs.putIfAbsent(mappedClass, codec);
            if (old != null) {
                codec = old;
            }
        }
        return codec;
    }


    <T> TypeCodec<T> getUDTCodec(Class<T> mappedClass) {
        return getUDTCodec(mappedClass, defaultConfiguration);
    }

    @SuppressWarnings("unchecked")
    private <T> T getAccessor(Class<T> klass, MappingConfiguration configuration) {
        configuration = new MappingConfiguration(configuration);
        T accessor = (T) accessors.get(klass);
        if (accessor == null) {
            AccessorMapper<T> mapper = AnnotationParser.parseAccessor(klass, this, configuration);
            mapper.prepare(this);
            accessor = mapper.createProxy();
            T old = (T) accessors.putIfAbsent(klass, accessor);
            if (old != null) {
                accessor = old;
            }
        }
        return accessor;
    }
}
