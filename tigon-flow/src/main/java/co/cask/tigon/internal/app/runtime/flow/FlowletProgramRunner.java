/*
 * Copyright © 2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.tigon.internal.app.runtime.flow;

import co.cask.tigon.api.annotation.Batch;
import co.cask.tigon.api.annotation.HashPartition;
import co.cask.tigon.api.annotation.ProcessInput;
import co.cask.tigon.api.annotation.RoundRobin;
import co.cask.tigon.api.annotation.Tick;
import co.cask.tigon.api.flow.FlowSpecification;
import co.cask.tigon.api.flow.FlowletDefinition;
import co.cask.tigon.api.flow.flowlet.Callback;
import co.cask.tigon.api.flow.flowlet.FailurePolicy;
import co.cask.tigon.api.flow.flowlet.FailureReason;
import co.cask.tigon.api.flow.flowlet.Flowlet;
import co.cask.tigon.api.flow.flowlet.FlowletSpecification;
import co.cask.tigon.api.flow.flowlet.InputContext;
import co.cask.tigon.api.flow.flowlet.OutputEmitter;
import co.cask.tigon.app.program.Program;
import co.cask.tigon.app.program.ProgramType;
import co.cask.tigon.app.queue.QueueReader;
import co.cask.tigon.app.queue.QueueSpecification;
import co.cask.tigon.app.queue.QueueSpecificationGenerator.Node;
import co.cask.tigon.async.ExecutorUtils;
import co.cask.tigon.conf.CConfiguration;
import co.cask.tigon.data.queue.ConsumerConfig;
import co.cask.tigon.data.queue.DequeueStrategy;
import co.cask.tigon.data.queue.QueueClientFactory;
import co.cask.tigon.data.queue.QueueConsumer;
import co.cask.tigon.data.queue.QueueName;
import co.cask.tigon.data.queue.QueueProducer;
import co.cask.tigon.data.transaction.queue.QueueMetrics;
import co.cask.tigon.internal.app.queue.QueueReaderFactory;
import co.cask.tigon.internal.app.queue.RoundRobinQueueReader;
import co.cask.tigon.internal.app.queue.SimpleQueueSpecificationGenerator;
import co.cask.tigon.internal.app.runtime.DataFabricFacade;
import co.cask.tigon.internal.app.runtime.DataFabricFacadeFactory;
import co.cask.tigon.internal.app.runtime.MetricsFieldSetter;
import co.cask.tigon.internal.app.runtime.ProgramController;
import co.cask.tigon.internal.app.runtime.ProgramOptionConstants;
import co.cask.tigon.internal.app.runtime.ProgramOptions;
import co.cask.tigon.internal.app.runtime.ProgramRunner;
import co.cask.tigon.internal.io.ByteBufferInputStream;
import co.cask.tigon.internal.io.DatumWriterFactory;
import co.cask.tigon.internal.io.ReflectionDatumReader;
import co.cask.tigon.internal.io.Schema;
import co.cask.tigon.internal.io.SchemaGenerator;
import co.cask.tigon.internal.io.UnsupportedTypeException;
import co.cask.tigon.internal.lang.Reflections;
import co.cask.tigon.internal.specification.FlowletMethod;
import co.cask.tigon.io.BinaryDecoder;
import co.cask.tigon.lang.InstantiatorFactory;
import co.cask.tigon.lang.PropertyFieldSetter;
import co.cask.tigon.logging.callback.CAppender;
import co.cask.tigon.logging.common.LogWriter;
import co.cask.tigon.metrics.MetricsCollectionService;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.Service;
import com.google.inject.Inject;
import org.apache.twill.api.RunId;
import org.apache.twill.api.ServiceAnnouncer;
import org.apache.twill.common.Cancellable;
import org.apache.twill.common.Threads;
import org.apache.twill.discovery.DiscoveryServiceClient;
import org.apache.twill.internal.RunIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;

/**
 *
 */
public final class FlowletProgramRunner implements ProgramRunner {

  private static final Logger LOG = LoggerFactory.getLogger(FlowletProgramRunner.class);

  private final SchemaGenerator schemaGenerator;
  private final DatumWriterFactory datumWriterFactory;
  private final DataFabricFacadeFactory dataFabricFacadeFactory;
  private final QueueReaderFactory queueReaderFactory;
  private final MetricsCollectionService metricsCollectionService;
  private final DiscoveryServiceClient discoveryServiceClient;
  private final CConfiguration configuration;
  private final ServiceAnnouncer serviceAnnouncer;

  @Inject
  public FlowletProgramRunner(SchemaGenerator schemaGenerator,
                              DatumWriterFactory datumWriterFactory,
                              DataFabricFacadeFactory dataFabricFacadeFactory,
                              QueueReaderFactory queueReaderFactory,
                              MetricsCollectionService metricsCollectionService,
                              DiscoveryServiceClient discoveryServiceClient,
                              CConfiguration configuration, ServiceAnnouncer serviceAnnouncer) {
    this.schemaGenerator = schemaGenerator;
    this.datumWriterFactory = datumWriterFactory;
    this.dataFabricFacadeFactory = dataFabricFacadeFactory;
    this.queueReaderFactory = queueReaderFactory;
    this.metricsCollectionService = metricsCollectionService;
    this.discoveryServiceClient = discoveryServiceClient;
    this.configuration = configuration;
    this.serviceAnnouncer = serviceAnnouncer;
  }

  @SuppressWarnings("unused")
  @Inject(optional = true)
  void setLogWriter(LogWriter logWriter) {
    CAppender.logWriter = logWriter;
  }

  @SuppressWarnings("unchecked")
  @Override
  public ProgramController run(Program program, ProgramOptions options) {
    BasicFlowletContext flowletContext = null;
    try {
      // Extract and verify parameters
      String flowletName = options.getName();

      int instanceId = Integer.parseInt(options.getArguments().getOption(ProgramOptionConstants.INSTANCE_ID, "-1"));
      Preconditions.checkArgument(instanceId >= 0, "Missing instance Id");

      int instanceCount = Integer.parseInt(options.getArguments().getOption(ProgramOptionConstants.INSTANCES, "0"));
      Preconditions.checkArgument(instanceCount > 0, "Invalid or missing instance count");

      String runIdOption = options.getArguments().getOption(ProgramOptionConstants.RUN_ID);
      Preconditions.checkNotNull(runIdOption, "Missing runId");
      RunId runId = RunIds.fromString(runIdOption);

      FlowSpecification flowSpec = program.getSpecification();
      ProgramType processorType = program.getType();
      Preconditions.checkNotNull(processorType, "Missing processor type.");
      Preconditions.checkArgument(processorType == ProgramType.FLOW, "Only FLOW process type is supported.");

      String processorName = program.getName();
      Preconditions.checkNotNull(processorName, "Missing processor name.");

      FlowletDefinition flowletDef = flowSpec.getFlowlets().get(flowletName);
      Preconditions.checkNotNull(flowletDef, "Definition missing for flowlet \"%s\"", flowletName);

      Class<?> clz = Class.forName(flowletDef.getFlowletSpec().getClassName(), true,
                                   program.getClassLoader());
      Preconditions.checkArgument(Flowlet.class.isAssignableFrom(clz), "%s is not a Flowlet.", clz);

      Class<? extends Flowlet> flowletClass = (Class<? extends Flowlet>) clz;

      // Creates tx related objects
      DataFabricFacade dataFabricFacade = dataFabricFacadeFactory.create(program);

      // Creates flowlet context
      flowletContext = new BasicFlowletContext(program, flowletName, instanceId, runId, instanceCount,
                                               options.getUserArguments(), flowletDef.getFlowletSpec(),
                                               metricsCollectionService, dataFabricFacade, serviceAnnouncer);



      // Creates QueueSpecification
      Table<Node, String, Set<QueueSpecification>> queueSpecs = new SimpleQueueSpecificationGenerator().create(
        flowSpec);

      Flowlet flowlet = new InstantiatorFactory(false).get(TypeToken.of(flowletClass)).create();
      TypeToken<? extends Flowlet> flowletType = TypeToken.of(flowletClass);

      // Set the context classloader to the Tigon classloader. It is needed for the DatumWriterFactory be able
      // to load Tigon classes
      Thread.currentThread().setContextClassLoader(FlowletProgramRunner.class.getClassLoader());

      // Inject DataSet, OutputEmitter, Metric fields
      Reflections.visit(flowlet, TypeToken.of(flowlet.getClass()),
                        new PropertyFieldSetter(flowletDef.getFlowletSpec().getProperties()),
                        new MetricsFieldSetter(flowletContext.getMetrics()),
                        new OutputEmitterFieldSetter(outputEmitterFactory(flowletContext, flowletName,
                                                                          dataFabricFacade, queueSpecs))
      );

      ImmutableList.Builder<ConsumerSupplier<?>> queueConsumerSupplierBuilder = ImmutableList.builder();
      Collection<ProcessSpecification> processSpecs =
        createProcessSpecification(flowletContext, flowletType,
                                   processMethodFactory(flowlet),
                                   processSpecificationFactory(flowletContext, dataFabricFacade, queueReaderFactory,
                                                               flowletName, queueSpecs, queueConsumerSupplierBuilder,
                                                               createSchemaCache(program)),
                                   Lists.<ProcessSpecification>newLinkedList());
      List<ConsumerSupplier<?>> consumerSuppliers = queueConsumerSupplierBuilder.build();

      // Create the flowlet driver
      AtomicReference<FlowletProgramController> controllerRef = new AtomicReference<FlowletProgramController>();
      Service serviceHook = createServiceHook(flowletName, consumerSuppliers, controllerRef);
      FlowletProcessDriver driver = new FlowletProcessDriver(flowlet, flowletContext, processSpecs,
                                                             createCallback(flowlet, flowletDef.getFlowletSpec()),
                                                             dataFabricFacade, serviceHook);

      FlowletProgramController controller = new FlowletProgramController(program.getName(), flowletName,
                                                                         flowletContext, driver, consumerSuppliers);
      controllerRef.set(controller);

      LOG.info("Starting flowlet: {}", flowletContext);
      driver.start();
      LOG.info("Flowlet started: {}", flowletContext);

      return controller;

    } catch (Exception e) {
      // something went wrong before the flowlet even started. Make sure we release all resources (datasets, ...)
      // of the flowlet context.
      if (flowletContext != null) {
        flowletContext.close();
      }
      throw Throwables.propagate(e);
    }
  }

  /**
   * Creates all {@link ProcessSpecification} for the process methods of the flowlet class.
   *
   * @param flowletType Type of the flowlet class represented by {@link com.google.common.reflect.TypeToken}.
   * @param processMethodFactory A {@link ProcessMethodFactory} for creating {@link ProcessMethod}.
   * @param processSpecFactory A {@link ProcessSpecificationFactory} for creating {@link ProcessSpecification}.
   * @param result A {@link java.util.Collection} for storing newly created {@link ProcessSpecification}.
   * @return The same {@link java.util.Collection} as the {@code result} parameter.
   */
  @SuppressWarnings("unchecked")
  private Collection<ProcessSpecification> createProcessSpecification(BasicFlowletContext flowletContext,
                                                                      TypeToken<? extends Flowlet> flowletType,
                                                                      ProcessMethodFactory processMethodFactory,
                                                                      ProcessSpecificationFactory processSpecFactory,
                                                                      Collection<ProcessSpecification> result)
    throws NoSuchMethodException {

    Set<FlowletMethod> seenMethods = Sets.newHashSet();

    // Walk up the hierarchy of flowlet class to get all ProcessInput and Tick methods
    for (TypeToken<?> type : flowletType.getTypes().classes()) {
      if (type.getRawType().equals(Object.class)) {
        break;
      }

      // Extracts all process and tick methods
      for (Method method : type.getRawType().getDeclaredMethods()) {
        if (!seenMethods.add(new FlowletMethod(method, flowletType))) {
          // The method is already seen. It can only happen if a children class override a parent class method and
          // is visting the parent method, since the method visiting order is always from the leaf class walking
          // up the class hierarchy.
          continue;
        }

        ProcessInput processInputAnnotation = method.getAnnotation(ProcessInput.class);
        Tick tickAnnotation = method.getAnnotation(Tick.class);

        if (processInputAnnotation == null && tickAnnotation == null) {
          // Neither a process nor a tick method.
          continue;
        }

        int maxRetries = (tickAnnotation == null) ? processInputAnnotation.maxRetries() : tickAnnotation.maxRetries();

        ProcessMethod processMethod = processMethodFactory.create(method, maxRetries);
        Set<String> inputNames;
        Schema schema;
        TypeToken<?> dataType;
        ConsumerConfig consumerConfig;
        int batchSize = 1;

        if (tickAnnotation != null) {
          inputNames = ImmutableSet.of();
          consumerConfig = new ConsumerConfig(0, 0, 1, DequeueStrategy.FIFO, null);
          schema = Schema.of(Schema.Type.NULL);
          dataType = TypeToken.of(void.class);
        } else {
          inputNames = Sets.newHashSet(processInputAnnotation.value());
          if (inputNames.isEmpty()) {
            // If there is no input name, it would be ANY_INPUT
            inputNames.add(FlowletDefinition.ANY_INPUT);
          }
          // If batch mode then generate schema for Iterator's parameter type
          dataType = flowletType.resolveType(method.getGenericParameterTypes()[0]);
          consumerConfig = getConsumerConfig(flowletContext, method);
          Integer processBatchSize = getBatchSize(method);

          if (processBatchSize != null) {
            if (dataType.getRawType().equals(Iterator.class)) {
              Preconditions.checkArgument(dataType.getType() instanceof ParameterizedType,
                                          "Only ParameterizedType is supported for batch Iterator.");
              dataType = flowletType.resolveType(((ParameterizedType) dataType.getType()).getActualTypeArguments()[0]);
            }
            batchSize = processBatchSize;
          }

          try {
            schema = schemaGenerator.generate(dataType.getType());
          } catch (UnsupportedTypeException e) {
            throw Throwables.propagate(e);
          }
        }

        ProcessSpecification processSpec = processSpecFactory.create(inputNames, schema, dataType, processMethod,
                                                                     consumerConfig, batchSize, tickAnnotation);
        // Add processSpec
        if (processSpec != null) {
          result.add(processSpec);
        }
      }
    }
    Preconditions.checkArgument(!result.isEmpty(), "No process or tick method found for " + flowletType);
    return result;
  }

  /**
   * Creates a {@link ConsumerConfig} based on the method annotation and the flowlet context.
   * @param flowletContext Runtime context of the flowlet.
   * @param method The process method to inspect.
   * @return A new instance of {@link ConsumerConfig}.
   */
  private ConsumerConfig getConsumerConfig(BasicFlowletContext flowletContext, Method method) {
    // Determine input queue partition type
    HashPartition hashPartition = method.getAnnotation(HashPartition.class);
    RoundRobin roundRobin = method.getAnnotation(RoundRobin.class);
    DequeueStrategy strategy = DequeueStrategy.FIFO;
    String hashKey = null;

    Preconditions.checkArgument(!(hashPartition != null && roundRobin != null),
                                "Only one strategy allowed for process() method: %s", method.getName());

    if (hashPartition != null) {
      strategy = DequeueStrategy.HASH;
      hashKey = hashPartition.value();
      Preconditions.checkArgument(!hashKey.isEmpty(), "Partition key cannot be empty: %s", method.getName());
    } else if (roundRobin != null) {
      strategy = DequeueStrategy.ROUND_ROBIN;
    }

    return new ConsumerConfig(flowletContext.getGroupId(), flowletContext.getInstanceId(),
                              flowletContext.getInstanceCount(), strategy, hashKey);
  }

  /**
   * Returns the user specify batch size or {@code null} if not specified.
   */
  private Integer getBatchSize(Method method) {
    // Determine queue batch size, if any
    Batch batch = method.getAnnotation(Batch.class);
    if (batch != null) {
      int batchSize = batch.value();
      Preconditions.checkArgument(batchSize > 0, "Batch size should be > 0: %s", method.getName());
      return batchSize;
    }
    return null;
  }

  private int getNumGroups(Iterable<QueueSpecification> queueSpecs, QueueName queueName) {
    int numGroups = 0;
    for (QueueSpecification queueSpec : queueSpecs) {
      if (queueName.equals(queueSpec.getQueueName())) {
        numGroups++;
      }
    }
    return numGroups;
  }

  private Callback createCallback(Flowlet flowlet, FlowletSpecification flowletSpec) {
    if (flowlet instanceof Callback) {
      return (Callback) flowlet;
    }
    final FailurePolicy failurePolicy = flowletSpec.getFailurePolicy();
    return new Callback() {
      @Override
      public void onSuccess(Object input, InputContext inputContext) {
        // No-op
      }

      @Override
      public FailurePolicy onFailure(Object input, InputContext inputContext, FailureReason reason) {
        return failurePolicy;
      }
    };
  }

  private OutputEmitterFactory outputEmitterFactory(final BasicFlowletContext flowletContext,
                                                    final String flowletName,
                                                    final QueueClientFactory queueClientFactory,
                                                    final Table<Node, String, Set<QueueSpecification>> queueSpecs) {
    return new OutputEmitterFactory() {
      @Override
      public <T> OutputEmitter<T> create(String outputName, TypeToken<T> type) {
        try {
          Schema schema = schemaGenerator.generate(type.getType());
          Node flowlet = Node.flowlet(flowletName);
          for (QueueSpecification queueSpec : Iterables.concat(queueSpecs.row(flowlet).values())) {
            if (queueSpec.getQueueName().getSimpleName().equals(outputName)
                && queueSpec.getOutputSchema().equals(schema)) {

              final String queueMetricsName = "process.events.out";
              final String queueMetricsTag = queueSpec.getQueueName().getSimpleName();
              QueueProducer producer = queueClientFactory.createProducer(queueSpec.getQueueName(), new QueueMetrics() {
                @Override
                public void emitEnqueue(int count) {
                  flowletContext.getProgramMetrics().gauge(queueMetricsName, count, queueMetricsTag);
                }

                @Override
                public void emitEnqueueBytes(int bytes) {
                  // no-op
                }
              });
              return new DatumOutputEmitter<T>(producer, schema, datumWriterFactory.create(type, schema));
            }
          }

          throw new IllegalArgumentException(String.format("No queue specification found for %s, %s",
                                                           flowletName, type));

        } catch (Exception e) {
          throw Throwables.propagate(e);
        }
      }
    };
  }

  private ProcessMethodFactory processMethodFactory(final Flowlet flowlet) {
    return new ProcessMethodFactory() {
      @Override
      public <T> ProcessMethod<T> create(Method method, int maxRetries) {
        return ReflectionProcessMethod.create(flowlet, method, maxRetries);
      }
    };
  }

  private ProcessSpecificationFactory processSpecificationFactory(
    final BasicFlowletContext flowletContext, final DataFabricFacade dataFabricFacade,
    final QueueReaderFactory queueReaderFactory, final String flowletName,
    final Table<Node, String, Set<QueueSpecification>> queueSpecs,
    final ImmutableList.Builder<ConsumerSupplier<?>> queueConsumerSupplierBuilder,
    final SchemaCache schemaCache) {

    return new ProcessSpecificationFactory() {
      @Override
      public <T> ProcessSpecification create(Set<String> inputNames, Schema schema, TypeToken<T> dataType,
                                             ProcessMethod<T> method, ConsumerConfig consumerConfig, int batchSize,
                                             Tick tickAnnotation) {
        List<QueueReader<T>> queueReaders = Lists.newLinkedList();

        for (Map.Entry<Node, Set<QueueSpecification>> entry : queueSpecs.column(flowletName).entrySet()) {
          for (QueueSpecification queueSpec : entry.getValue()) {
            final QueueName queueName = queueSpec.getQueueName();

            if (queueSpec.getInputSchema().equals(schema)
              && (inputNames.contains(queueName.getSimpleName())
              || inputNames.contains(FlowletDefinition.ANY_INPUT))) {

                int numGroups = getNumGroups(Iterables.concat(queueSpecs.row(entry.getKey()).values()), queueName);
                Function<ByteBuffer, T> decoder =
                  wrapInputDecoder(flowletContext, queueName, createInputDatumDecoder(dataType, schema, schemaCache));

                ConsumerSupplier<QueueConsumer> consumerSupplier = ConsumerSupplier.create(dataFabricFacade, queueName,
                                                                                            consumerConfig, numGroups);
                queueConsumerSupplierBuilder.add(consumerSupplier);
                queueReaders.add(queueReaderFactory.createQueueReader(consumerSupplier, batchSize, decoder));

            }
          }
        }

        // If inputs is needed but there is no available input queue, return null
        if (!inputNames.isEmpty() && queueReaders.isEmpty()) {
          return null;
        }
        return new ProcessSpecification<T>(new RoundRobinQueueReader<T>(queueReaders), method, tickAnnotation);
      }
    };
  }

  private <T> Function<ByteBuffer, T> createInputDatumDecoder(final TypeToken<T> dataType, final Schema schema,
                                                              final SchemaCache schemaCache) {
    final ReflectionDatumReader<T> datumReader = new ReflectionDatumReader<T>(schema, dataType);
    final ByteBufferInputStream byteBufferInput = new ByteBufferInputStream(null);
    final BinaryDecoder decoder = new BinaryDecoder(byteBufferInput);

    return new Function<ByteBuffer, T>() {
      @Nullable
      @Override
      public T apply(ByteBuffer input) {
        byteBufferInput.reset(input);
        try {
          final Schema sourceSchema = schemaCache.get(input);
          Preconditions.checkNotNull(sourceSchema, "Fail to find source schema.");
          return datumReader.read(decoder, sourceSchema);
        } catch (IOException e) {
          throw Throwables.propagate(e);
        }
      }

      @Override
      public String toString() {
        return Objects.toStringHelper(this)
          .add("dataType", dataType)
          .add("schema", schema)
          .toString();
      }
    };
  }

  private <S, T> Function<S, T> wrapInputDecoder(final BasicFlowletContext context,
                                                 final QueueName queueName,
                                                 final Function<S, T> inputDecoder) {
    final String eventsMetricsName = "process.events.in";
    final String eventsMetricsTag = queueName.getSimpleName();
    return new Function<S, T>() {
      @Override
      public T apply(S source) {
        context.getProgramMetrics().gauge(eventsMetricsName, 1, eventsMetricsTag);
        context.getProgramMetrics().gauge("process.tuples.read", 1, eventsMetricsTag);
        return inputDecoder.apply(source);
      }
    };
  }


  private SchemaCache createSchemaCache(Program program) throws Exception {
    ImmutableSet.Builder<Schema> schemas = ImmutableSet.builder();

    for (FlowletDefinition flowletDef : program.getSpecification().getFlowlets().values()) {
      schemas.addAll(Iterables.concat(flowletDef.getInputs().values()));
      schemas.addAll(Iterables.concat(flowletDef.getOutputs().values()));
    }

    return new SchemaCache(schemas.build(), program.getClassLoader());
  }

  /**
   * Create a initializer to be executed during the flowlet driver initialization.
   */
  private Service createServiceHook(String flowletName, Iterable<ConsumerSupplier<?>> consumerSuppliers,
                                    AtomicReference<FlowletProgramController> controller) {
    final List<String> streams = Lists.newArrayList();
    for (ConsumerSupplier<?> consumerSupplier : consumerSuppliers) {
      QueueName queueName = consumerSupplier.getQueueName();
      if (queueName.isStream()) {
        streams.add(queueName.getSimpleName());
      }
    }

    // If no stream, returns a no-op Service
    if (streams.isEmpty()) {
      return new AbstractService() {
        @Override
        protected void doStart() {
          notifyStarted();
        }

        @Override
        protected void doStop() {
          notifyStopped();
        }
      };
    }
    return new FlowletServiceHook(flowletName, streams, controller);
  }

  private static interface ProcessMethodFactory {
    <T> ProcessMethod<T> create(Method method, int maxRetries);
  }

  private static interface ProcessSpecificationFactory {
    /**
     * Returns a {@link ProcessSpecification} for invoking the given process method. {@code null} is returned if
     * no input is available for the given method.
     */
    <T> ProcessSpecification create(Set<String> inputNames, Schema schema, TypeToken<T> dataType,
                                    ProcessMethod<T> method, ConsumerConfig consumerConfig, int batchSize,
                                    Tick tickAnnotation);
  }

  /**
   * This service is for start/stop listening to changes in stream property,
   * so that it can react to changes and properly reconfigure stream consumers used by
   * the flowlet. This hook is provided to {@link FlowletProcessDriver} and being start/stop
   * when the driver start/stop.
   */
  private static final class FlowletServiceHook extends AbstractService {

    private final List<String> streams;
    private final AtomicReference<FlowletProgramController> controller;
    private final Executor executor;
    private final Lock suspendLock = new ReentrantLock();
    private Cancellable cancellable;

    private FlowletServiceHook(final String flowletName, List<String> streams,
                               AtomicReference<FlowletProgramController> controller) {
      this.streams = streams;
      this.controller = controller;
      this.executor = ExecutorUtils.newThreadExecutor(Threads.createDaemonThreadFactory("flowlet-stream-update-%d"));
    }

    @Override
    protected void doStart() {
      final List<Cancellable> cancellables = Lists.newArrayList();
      this.cancellable = new Cancellable() {
        @Override
        public void cancel() {
          for (Cancellable c : cancellables) {
            c.cancel();
          }
        }
      };

      notifyStarted();
    }

    @Override
    protected void doStop() {
      if (cancellable != null) {
        cancellable.cancel();
      }
      notifyStopped();
    }

    private void suspendAndResume() {
      executor.execute(new Runnable() {
        @Override
        public void run() {
          suspendLock.lock();
          try {
            controller.get().suspend().get();
            controller.get().resume().get();
          } catch (Exception e) {
            LOG.error("Failed to suspend and resume flowlet.", e);
          } finally {
            suspendLock.unlock();
          }
        }
      });
    }
  }
}
