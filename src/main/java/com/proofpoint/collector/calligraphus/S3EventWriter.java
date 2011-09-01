package com.proofpoint.collector.calligraphus;

import com.google.common.base.Function;
import com.google.common.collect.MapMaker;
import com.google.common.io.CountingOutputStream;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.proofpoint.experimental.units.DataSize;
import com.proofpoint.log.Logger;
import com.proofpoint.units.Duration;
import org.codehaus.jackson.JsonEncoding;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.map.ObjectMapper;

import javax.annotation.Nullable;
import javax.annotation.PreDestroy;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class S3EventWriter
        implements EventWriter
{
    private static final Duration CHECK_DELAY = new Duration(5, TimeUnit.SECONDS);
    private static final Logger log = Logger.get(S3EventWriter.class);

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1,
            new ThreadFactoryBuilder().setNameFormat("S3EventWriter-%s").build());

    private final S3Uploader uploader;
    private final EventPartitioner partitioner;
    private final ObjectMapper objectMapper;
    private final Duration maxBufferTime;
    private final DataSize targetFileSize;

    private final ConcurrentMap<EventPartition, OutputPartition> outputFiles = new MapMaker()
            .makeComputingMap(new Function<EventPartition, OutputPartition>()
            {
                @Override
                public OutputPartition apply(@Nullable EventPartition partition)
                {
                    return new OutputPartition(partition, uploader, objectMapper, targetFileSize, maxBufferTime);
                }
            });

    public S3EventWriter(S3Uploader uploader, EventPartitioner partitioner, ObjectMapper objectMapper, ServerConfig config)
    {
        this.uploader = uploader;
        this.partitioner = partitioner;
        this.objectMapper = objectMapper;
        this.maxBufferTime = config.getMaxBufferTime();
        this.targetFileSize = config.getTargetFileSize();

        // roll files that have been open too long
        Runnable closer = new Runnable()
        {
            @Override
            public void run()
            {
                for (OutputPartition partition : outputFiles.values()) {
                    try {
                        if (partition.isAtMaxAge()) {
                            partition.close();
                        }
                    }
                    catch (IOException e) {
                        log.error(e, "close output partition failed");
                    }
                }
            }
        };
        executor.scheduleAtFixedRate(closer, 0, (long) CHECK_DELAY.toMillis(), TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("UnusedDeclaration")
    @PreDestroy
    public void destroy()
            throws IOException
    {
        executor.shutdown();
        for (OutputPartition partition : outputFiles.values()) {
            partition.close();
        }
    }

    @Override
    public void write(Event event)
            throws IOException
    {
        EventPartition partition = partitioner.getPartition(event);

        outputFiles.get(partition).write(event);
    }

    private static class OutputPartition
    {
        private final EventPartition eventPartition;
        private final S3Uploader uploader;
        private final ObjectMapper objectMapper;
        private final DataSize targetFileSize;
        private final Duration maxBufferTime;

        private File file;
        private CountingOutputStream output;
        private JsonGenerator generator;
        private long createdTime;

        public OutputPartition(EventPartition eventPartition,
                S3Uploader uploader,
                ObjectMapper objectMapper,
                DataSize targetFileSize,
                Duration maxBufferTime)
        {
            this.eventPartition = eventPartition;
            this.uploader = uploader;
            this.objectMapper = objectMapper;
            this.targetFileSize = targetFileSize;
            this.maxBufferTime = maxBufferTime;
        }

        private synchronized void open()
                throws IOException
        {
            if (generator == null) {
                return;
            }

            file = new File("");
            output = new CountingOutputStream(new FileOutputStream(file));
            generator = objectMapper.getJsonFactory().createJsonGenerator(output, JsonEncoding.UTF8);
            createdTime = System.nanoTime();
        }

        public synchronized void write(Event event)
                throws IOException
        {
            open();

            generator.writeObject(event);

            // roll file if it is over the target size or max age
            if ((output.getCount() >= targetFileSize.toBytes()) || (isAtMaxAge())) {
                close();
            }
        }

        public synchronized void close()
                throws IOException
        {
            if (generator == null) {
                return;
            }

            generator.close();
            output.close();

            uploader.enqueueUpload(eventPartition, file);

            file = null;
            output = null;
            generator = null;
        }

        public synchronized boolean isAtMaxAge()
        {
            return (generator != null) && (Duration.nanosSince(createdTime).compareTo(maxBufferTime) > 0);
        }
    }
}
