package org.embulk.output;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigException;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.spi.Buffer;
import org.embulk.spi.Exec;
import org.embulk.spi.FileOutput;
import org.embulk.spi.FileOutputPlugin;
import org.embulk.spi.TransactionalFileOutput;
import org.slf4j.Logger;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.google.common.base.Optional;

public class Larges3FileOutputPlugin
        implements FileOutputPlugin
{
    public interface PluginTask
            extends Task
    {
        @Config("path_prefix")
        String getPathPrefix();

        @Config("file_ext")
        String getFileNameExtension();

        @Config("sequence_format")
        @ConfigDefault("\".%03d.%02d\"")
        String getSequenceFormat();

        @Config("bucket")
        String getBucket();

        @Config("endpoint")
        @ConfigDefault("null")
        Optional<String> getEndpoint();

        @Config("access_key_id")
        @ConfigDefault("null")
        Optional<String> getAccessKeyId();

        @Config("secret_access_key")
        @ConfigDefault("null")
        Optional<String> getSecretAccessKey();

        @Config("proxy_host")
        @ConfigDefault("null")
        Optional<String> getProxyHost();

        @Config("proxy_port")
        @ConfigDefault("null")
        Optional<Integer> getProxyPort();

        @Config("tmp_path")
        @ConfigDefault("null")
        Optional<String> getTempPath();

        @Config("tmp_path_prefix")
        @ConfigDefault("\"embulk-output-s3-\"")
        String getTempPathPrefix();

        @Config("canned_acl")
        @ConfigDefault("null")
        Optional<CannedAccessControlList> getCannedAccessControlList();
    }

    public static class S3FileOutput
            implements FileOutput,
            TransactionalFileOutput
    {
        private final Logger log = Exec.getLogger(Larges3FileOutputPlugin.class);

        private final String bucket;
        private final String pathPrefix;
        private final String sequenceFormat;
        private final String fileNameExtension;
        private final String tempPathPrefix;
        private final Optional<CannedAccessControlList> cannedAccessControlListOptional;

        private int taskIndex;
        private int fileIndex;
        private AmazonS3Client client;
        private OutputStream current;
        private Path tempFilePath;
        private String tempPath = null;

        private static AmazonS3Client newS3Client(PluginTask task)
        {
            AmazonS3Client client;

            // TODO: Support more configurations.
            ClientConfiguration config = new ClientConfiguration();

            if (task.getProxyHost().isPresent()) {
                config.setProxyHost(task.getProxyHost().get());
            }

            if (task.getProxyPort().isPresent()) {
                config.setProxyPort(task.getProxyPort().get());
            }

            if (task.getAccessKeyId().isPresent()) {
                BasicAWSCredentials basicAWSCredentials = new BasicAWSCredentials(
                        task.getAccessKeyId().get(), task.getSecretAccessKey().get());

                client = new AmazonS3Client(basicAWSCredentials, config);
            }
            else {
                // Use default credential provider chain.
                client = new AmazonS3Client(config);
            }

            if (task.getEndpoint().isPresent()) {
                client.setEndpoint(task.getEndpoint().get());
            }

            return client;
        }

        public S3FileOutput(PluginTask task, int taskIndex)
        {
            this.taskIndex = taskIndex;
            this.client = newS3Client(task);
            this.bucket = task.getBucket();
            this.pathPrefix = task.getPathPrefix();
            this.sequenceFormat = task.getSequenceFormat();
            this.fileNameExtension = task.getFileNameExtension();
            this.tempPathPrefix = task.getTempPathPrefix();
            if (task.getTempPath().isPresent()) {
                this.tempPath = task.getTempPath().get();
            }
            this.cannedAccessControlListOptional = task.getCannedAccessControlList();
        }

        private static Path newTempFile(String tmpDir, String prefix)
                throws IOException
        {
            if (tmpDir == null) {
                return Files.createTempFile(prefix, null);
            }
            else {
                return Files.createTempFile(Paths.get(tmpDir), prefix, null); }
        }

        private void deleteTempFile()
        {
            if (tempFilePath == null) {
                return;
            }

            try {
                Files.delete(tempFilePath);
                tempFilePath = null;
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private String buildCurrentKey()
        {
            String sequence = String.format(sequenceFormat, taskIndex, fileIndex);
            return pathPrefix + sequence + fileNameExtension;
        }

        private void putBigFile(Path from, String keyName)
        {
            // Create a list of UploadPartResponse objects. You get one of these
            // for each part upload.
            List<PartETag> partETags = new ArrayList<PartETag>();
            // Step 1: Initialize.
            InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(bucket, keyName);
            InitiateMultipartUploadResult initResponse = client.initiateMultipartUpload(initRequest);

            File file = new File(from.toString());
            long contentLength = file.length();
            long partSize = 52428800; // Set part size to 50 MB.

            try {
                // Step 2: Upload parts.
                long filePosition = 0;
                for (int i = 1; filePosition < contentLength; i++) {
                    // Last part can be less than 5 MB. Adjust part size.
                    partSize = Math.min(partSize, (contentLength - filePosition));

                    // Create request to upload a part.
                    UploadPartRequest uploadRequest = new UploadPartRequest()
                            .withBucketName(bucket).withKey(keyName)
                            .withUploadId(initResponse.getUploadId()).withPartNumber(i)
                            .withFileOffset(filePosition)
                            .withFile(file)
                            .withPartSize(partSize);

                    // Upload part and add response to our list.
                    partETags.add(client.uploadPart(uploadRequest).getPartETag());
                    filePosition += partSize;
                }

                // Step 3: Complete.
                CompleteMultipartUploadRequest compRequest = new
                        CompleteMultipartUploadRequest(
                        bucket,
                        keyName,
                        initResponse.getUploadId(),
                        partETags);

                client.completeMultipartUpload(compRequest);
            }
            catch (Exception e) {
                client.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, keyName, initResponse.getUploadId()));
            }
        }

        private void putFile(Path from, String key)
        {
            PutObjectRequest request = new PutObjectRequest(bucket, key, from.toFile());
            if (cannedAccessControlListOptional.isPresent()) {
                request.withCannedAcl(cannedAccessControlListOptional.get());
            }
            client.putObject(request);
        }

        private void closeCurrent()
        {
            if (current == null) {
                return;
            }

            try {
                putBigFile(tempFilePath, buildCurrentKey());
                //putFile(tempFilePath, buildCurrentKey());
                fileIndex++;
            }
            finally {
                try {
                    current.close();
                    current = null;
                }
                catch (IOException e) {
                    throw new RuntimeException(e);
                }
                finally {
                    deleteTempFile();
                }
            }
        }

        @Override
        public void nextFile()
        {
            closeCurrent();

            try {
                tempFilePath = newTempFile(tempPath, tempPathPrefix);

                log.info("Writing S3 file '{}'", buildCurrentKey());

                current = Files.newOutputStream(tempFilePath);
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void add(Buffer buffer)
        {
            if (current == null) {
                throw new IllegalStateException(
                        "nextFile() must be called before poll()");
            }

            try {
                current.write(buffer.array(), buffer.offset(), buffer.limit());
            }
            catch (IOException ex) {
                deleteTempFile();
                throw new RuntimeException(ex);
            }
            finally {
                buffer.release();
            }
        }

        @Override
        public void finish()
        {
            closeCurrent();
        }

        @Override
        public void close()
        {
            closeCurrent();
        }

        @Override
        public void abort()
        {
            deleteTempFile();
        }

        @Override
        public TaskReport commit()
        {
            TaskReport report = Exec.newTaskReport();
            return report;
        }
    }

    private void validateSequenceFormat(PluginTask task)
    {
        try {
            @SuppressWarnings("unused")
            String dontCare = String.format(Locale.ENGLISH,
                    task.getSequenceFormat(), 0, 0);
        }
        catch (IllegalFormatException ex) {
            throw new ConfigException(
                    "Invalid sequence_format: parameter for file output plugin",
                    ex);
        }
    }

    @Override
    public ConfigDiff transaction(ConfigSource config, int taskCount,
                                  Control control)
    {
        PluginTask task = config.loadConfig(PluginTask.class);

        validateSequenceFormat(task);

        return resume(task.dump(), taskCount, control);
    }

    @Override
    public ConfigDiff resume(TaskSource taskSource, int taskCount,
                             Control control)
    {
        control.run(taskSource);
        return Exec.newConfigDiff();
    }

    @Override
    public void cleanup(TaskSource taskSource, int taskCount,
                        List<TaskReport> successTaskReports)
    {
    }

    @Override
    public TransactionalFileOutput open(TaskSource taskSource, int taskIndex)
    {
        PluginTask task = taskSource.loadTask(PluginTask.class);

        return new S3FileOutput(task, taskIndex);
    }
}
