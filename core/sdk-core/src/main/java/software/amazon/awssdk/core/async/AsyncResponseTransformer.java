/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.core.async;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import software.amazon.awssdk.annotations.SdkProtectedApi;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.core.FileTransformerConfiguration;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.SplittingTransformerConfiguration;
import software.amazon.awssdk.core.internal.async.ByteArrayAsyncResponseTransformer;
import software.amazon.awssdk.core.internal.async.DefaultAsyncResponseTransformerSplitResult;
import software.amazon.awssdk.core.internal.async.FileAsyncResponseTransformer;
import software.amazon.awssdk.core.internal.async.InputStreamResponseTransformer;
import software.amazon.awssdk.core.internal.async.PublisherAsyncResponseTransformer;
import software.amazon.awssdk.core.internal.async.SplittingTransformer;
import software.amazon.awssdk.utils.Validate;
import software.amazon.awssdk.utils.builder.CopyableBuilder;
import software.amazon.awssdk.utils.builder.ToCopyableBuilder;
import software.amazon.awssdk.utils.internal.EnumUtils;

/**
 * Callback interface to handle a streaming asynchronous response.
 * <p>
 * <h2>Synchronization</h2>
 * <p>
 * All operations, including those called on the {@link org.reactivestreams.Subscriber} of the stream are guaranteed to be
 * synchronized externally; i.e. no two methods on this interface or on the {@link org.reactivestreams.Subscriber} will be invoked
 * concurrently. It is <b>not</b> guaranteed that the methods will being invoked by the same thread.
 * <p>
 * <h2>Invocation Order</h2>
 * <p>
 * The methods are called in the following order:
 * <ul>
 *     <li>
 *     {@link #prepare()}: This method is always called first. Implementations should use this to setup or perform any
 *     cleanup necessary. <b>Note that this will be called upon each request attempt</b>. If the {@link CompletableFuture}
 *     returned from the previous invocation has already been completed, the implementation should return a new instance.
 *     </li>
 *     <li>
 *     {@link #onResponse}: If the response was received successfully, this method is called next.
 *     </li>
 *     <li>
 *     {@link #onStream(SdkPublisher)}: Called after {@code onResponse}. This is always invoked, even if the service
 *     operation response does not contain a body. If the response does not have a body, then the {@link SdkPublisher} will
 *     complete the subscription without signaling any elements.
 *     </li>
 *     <li>
 *     {@link #exceptionOccurred(Throwable)}: If there is an error sending the request. This method is called before {@link
 *     org.reactivestreams.Subscriber#onError(Throwable)}.
 *     </li>
 *     <li>
 *     {@link org.reactivestreams.Subscriber#onError(Throwable)}: If an error is encountered while the {@code Publisher} is
 *     publishing to a {@link org.reactivestreams.Subscriber}.
 *     </li>
 * </ul>
 * <p>
 * <h2>Retries</h2>
 * <p>
 * The transformer has the ability to trigger retries at any time by completing the {@link CompletableFuture} with an
 * exception that is deemed retryable by the configured {@link software.amazon.awssdk.core.retry.RetryPolicy}.
 *
 * @param <ResponseT> POJO response type.
 * @param <ResultT>   Type this response handler produces. I.E. the type you are transforming the response into.
 */
@SdkPublicApi
public interface AsyncResponseTransformer<ResponseT, ResultT> {
    /**
     * Initial call to enable any setup required before the response is handled.
     * <p>
     * Note that this will be called for each request attempt, up to the number of retries allowed by the configured
     * {@link software.amazon.awssdk.core.retry.RetryPolicy}.
     * <p>
     * This method is guaranteed to be called before the request is executed, and before {@link #onResponse(Object)} is signaled.
     *
     * @return The future holding the transformed response.
     */
    CompletableFuture<ResultT> prepare();

    /**
     * Called when the unmarshalled response object is ready.
     *
     * @param response The unmarshalled response.
     */
    void onResponse(ResponseT response);

    /**
     * Called when the response stream is ready.
     *
     * @param publisher The publisher.
     */
    void onStream(SdkPublisher<ByteBuffer> publisher);

    /**
     * Called when an error is encountered while making the request or receiving the response. Implementations should free up any
     * resources in this method. This method may be called multiple times during the lifecycle of a request if automatic retries
     * are enabled.
     *
     * @param error Error that occurred.
     */
    void exceptionOccurred(Throwable error);

    /**
     * Creates an {@link SplitResult} which contains an {@link SplittingTransformer} that splits the
     * {@link AsyncResponseTransformer} into multiple ones, publishing them as a {@link SdkPublisher}.
     *
     * @param splitConfig configuration for the split transformer
     * @return SplitAsyncResponseTransformer instance.
     * @see SplittingTransformer
     * @see SplitResult
     */
    default SplitResult<ResponseT, ResultT> split(SplittingTransformerConfiguration splitConfig) {
        Validate.notNull(splitConfig, "splitConfig must not be null");
        CompletableFuture<ResultT> future = new CompletableFuture<>();
        SdkPublisher<AsyncResponseTransformer<ResponseT, ResponseT>> transformer = SplittingTransformer
            .<ResponseT, ResultT>builder()
            .upstreamResponseTransformer(this)
            .maximumBufferSizeInBytes(splitConfig.bufferSizeInBytes())
            .resultFuture(future)
            .build();
        return AsyncResponseTransformer.SplitResult.<ResponseT, ResultT>builder()
                                            .publisher(transformer)
                                            .resultFuture(future)
                                            .build();
    }

    /**
     * Creates an {@link SplitResult} which contains an {@link SplittingTransformer} that splits the
     * {@link AsyncResponseTransformer} into multiple ones, publishing them as a {@link SdkPublisher}.
     *
     * @param splitConfig configuration for the split transformer
     * @return SplitAsyncResponseTransformer instance.
     * @see SplittingTransformer
     * @see SplitResult
     */
    default SplitResult<ResponseT, ResultT> split(Consumer<SplittingTransformerConfiguration.Builder> splitConfig) {
        SplittingTransformerConfiguration conf = SplittingTransformerConfiguration.builder()
                                                                                  .applyMutation(splitConfig)
                                                                                  .build();
        return split(conf);
    }

    /**
     * Each AsyncResponseTransformer should return a well-formed name that can be used to identify the implementation.
     * The Transformer name should only include alphanumeric characters.
     *
     * @return String containing the identifying name of this AsyncRequestTransformer.
     */
    default String name() {
        return TransformerType.UNKNOWN.getName();
    }

    /**
     * Creates an {@link AsyncResponseTransformer} that writes all the content to the given file. In the event of an error, the
     * SDK will attempt to delete the file (whatever has been written to it so far). If the file already exists, an exception will
     * be thrown.
     *
     * @param path        Path to file to write to.
     * @param <ResponseT> Pojo Response type.
     * @return AsyncResponseTransformer instance.
     * @see #toFile(Path, FileTransformerConfiguration)
     */
    static <ResponseT> AsyncResponseTransformer<ResponseT, ResponseT> toFile(Path path) {
        return new FileAsyncResponseTransformer<>(path);
    }

    /**
     * Creates an {@link AsyncResponseTransformer} that writes all the content to the given file with the specified
     * {@link FileTransformerConfiguration}.
     *
     * @param path        Path to file to write to.
     * @param config      configuration for the transformer
     * @param <ResponseT> Pojo Response type.
     * @return AsyncResponseTransformer instance.
     * @see FileTransformerConfiguration
     */
    static <ResponseT> AsyncResponseTransformer<ResponseT, ResponseT> toFile(Path path, FileTransformerConfiguration config) {
        return new FileAsyncResponseTransformer<>(path, config);
    }

    /**
     * This is a convenience method that creates an instance of the {@link FileTransformerConfiguration} builder, avoiding the
     * need to create one manually via {@link FileTransformerConfiguration#builder()}.
     *
     * @see #toFile(Path, FileTransformerConfiguration)
     */
    static <ResponseT> AsyncResponseTransformer<ResponseT, ResponseT> toFile(
        Path path, Consumer<FileTransformerConfiguration.Builder> config) {
        Validate.paramNotNull(config, "config");
        return new FileAsyncResponseTransformer<>(path, FileTransformerConfiguration.builder().applyMutation(config).build());
    }

    /**
     * Creates an {@link AsyncResponseTransformer} that writes all the content to the given file. In the event of an error, the
     * SDK will attempt to delete the file (whatever has been written to it so far). If the file already exists, an exception will
     * be thrown.
     *
     * @param file        File to write to.
     * @param <ResponseT> Pojo Response type.
     * @return AsyncResponseTransformer instance.
     */
    static <ResponseT> AsyncResponseTransformer<ResponseT, ResponseT> toFile(File file) {
        return toFile(file.toPath());
    }

    /**
     * Creates an {@link AsyncResponseTransformer} that writes all the content to the given file with the specified
     * {@link FileTransformerConfiguration}.
     *
     * @param file        File to write to.
     * @param config      configuration for the transformer
     * @param <ResponseT> Pojo Response type.
     * @return AsyncResponseTransformer instance.
     * @see FileTransformerConfiguration
     */
    static <ResponseT> AsyncResponseTransformer<ResponseT, ResponseT> toFile(File file, FileTransformerConfiguration config) {
        return new FileAsyncResponseTransformer<>(file.toPath(), config);
    }

    /**
     * This is a convenience method that creates an instance of the {@link FileTransformerConfiguration} builder, avoiding the
     * need to create one manually via {@link FileTransformerConfiguration#builder()}.
     *
     * @see #toFile(File, FileTransformerConfiguration)
     */
    static <ResponseT> AsyncResponseTransformer<ResponseT, ResponseT> toFile(
        File file, Consumer<FileTransformerConfiguration.Builder> config) {
        Validate.paramNotNull(config, "config");
        return new FileAsyncResponseTransformer<>(file.toPath(), FileTransformerConfiguration.builder()
                                                                                             .applyMutation(config)
                                                                                             .build());
    }

    /**
     * Creates an {@link AsyncResponseTransformer} that writes all content to a byte array.
     *
     * @param <ResponseT> Pojo response type.
     * @return AsyncResponseTransformer instance.
     */
    static <ResponseT> AsyncResponseTransformer<ResponseT, ResponseBytes<ResponseT>> toBytes() {
        return new ByteArrayAsyncResponseTransformer<>();
    }

    /**
     * Creates an {@link AsyncResponseTransformer} that publishes the response body content through a {@link ResponsePublisher},
     * which is an {@link SdkPublisher} that also contains a reference to the {@link SdkResponse} returned by the service.
     * <p>
     * When this transformer is used with an async client, the {@link CompletableFuture} that the client returns will be completed
     * once the {@link SdkResponse} is available and the response body <i>begins</i> streaming. This behavior differs from some
     * other transformers, like {@link #toFile(Path)} and {@link #toBytes()}, which only have their {@link CompletableFuture}
     * completed after the entire response body has finished streaming.
     * <p>
     * You are responsible for subscribing to this publisher and managing the associated back-pressure. Therefore, this
     * transformer is only recommended for advanced use cases.
     * <p>
     * Example usage:
     * <pre>
     * {@code
     *     CompletableFuture<ResponsePublisher<GetObjectResponse>> responseFuture =
     *         s3AsyncClient.getObject(getObjectRequest, AsyncResponseTransformer.toPublisher());
     *     ResponsePublisher<GetObjectResponse> responsePublisher = responseFuture.join();
     *     System.out.println(responsePublisher.response());
     *     CompletableFuture<Void> drainPublisherFuture = responsePublisher.subscribe(System.out::println);
     *     drainPublisherFuture.join();
     * }
     * </pre>
     *
     * @param <ResponseT> Pojo response type.
     * @return AsyncResponseTransformer instance.
     */
    static <ResponseT extends SdkResponse> AsyncResponseTransformer<ResponseT, ResponsePublisher<ResponseT>> toPublisher() {
        return new PublisherAsyncResponseTransformer<>();
    }

    /**
     * Creates an {@link AsyncResponseTransformer} that allows reading the response body content as an {@link InputStream}.
     * <p>
     * When this transformer is used with an async client, the {@link CompletableFuture} that the client returns will be completed
     * once the {@link SdkResponse} is available and the response body <i>begins</i> streaming. This behavior differs from some
     * other transformers, like {@link #toFile(Path)} and {@link #toBytes()}, which only have their {@link CompletableFuture}
     * completed after the entire response body has finished streaming.
     * <p>
     * You are responsible for performing blocking reads from this input stream and closing the stream when you are finished.
     * <p>
     * Example usage:
     * <pre>
     * {@code
     *     CompletableFuture<ResponseInputStream<GetObjectResponse>> responseFuture =
     *         s3AsyncClient.getObject(getObjectRequest, AsyncResponseTransformer.toBlockingInputStream());
     *     try (ResponseInputStream<GetObjectResponse> responseStream = responseFuture.join()) {
     *         responseStream.transferTo(System.out); // BLOCKS the calling thread
     *     }
     * }
     * </pre>
     */
    static <ResponseT extends SdkResponse>
        AsyncResponseTransformer<ResponseT, ResponseInputStream<ResponseT>> toBlockingInputStream() {
        return new InputStreamResponseTransformer<>();
    }

    /**
     * Helper interface containing the result of {@link AsyncResponseTransformer#split(SplittingTransformerConfiguration)
     * splitting} an AsyncResponseTransformer. This class holds both the publisher of the individual
     * {@code AsyncResponseTransformer<ResponseT, ResponseT>} and the {@code CompletableFuture <ResultT>} which will
     * complete when the {@code AsyncResponseTransformer} that was split itself would complete.
     *
     * @param <ResponseT> ResponseT of the original AsyncResponseTransformer that was split.
     * @param <ResultT>   ResultT of the original AsyncResponseTransformer that was split.
     * @see AsyncResponseTransformer#split(SplittingTransformerConfiguration)
     */
    interface SplitResult<ResponseT, ResultT>
        extends ToCopyableBuilder<AsyncResponseTransformer.SplitResult.Builder<ResponseT, ResultT>,
        AsyncResponseTransformer.SplitResult<ResponseT, ResultT>> {

        /**
         * The individual {@link AsyncResponseTransformer} will be available through the publisher returned by this method.
         *
         * @return the publisher which publishes the individual {@link AsyncResponseTransformer}
         */
        SdkPublisher<AsyncResponseTransformer<ResponseT, ResponseT>> publisher();

        /**
         * The future returned by this method will be completed when the future returned by calling the
         * {@link AsyncResponseTransformer#prepare()} method on the AsyncResponseTransformer which was split completes.
         *
         * @return The future
         */
        CompletableFuture<ResultT> resultFuture();

        static <ResponseT, ResultT> Builder<ResponseT, ResultT> builder() {
            return DefaultAsyncResponseTransformerSplitResult.builder();
        }

        interface Builder<ResponseT, ResultT>
            extends CopyableBuilder<AsyncResponseTransformer.SplitResult.Builder<ResponseT, ResultT>,
            AsyncResponseTransformer.SplitResult<ResponseT, ResultT>> {

            /**
             * @return the publisher which was configured on this Builder instance.
             */
            SdkPublisher<AsyncResponseTransformer<ResponseT, ResponseT>> publisher();

            /**
             * Sets the publisher publishing the individual {@link AsyncResponseTransformer}
             * @param publisher the publisher
             * @return an instance of this Builder
             */
            Builder<ResponseT, ResultT> publisher(SdkPublisher<AsyncResponseTransformer<ResponseT, ResponseT>> publisher);

            /**
             * @return The future which was configured an this Builder instance.
             */
            CompletableFuture<ResultT> resultFuture();

            /**
             * Sets the future that will be completed when the future returned by calling the
             * {@link AsyncResponseTransformer#prepare()} method on the AsyncResponseTransformer which was split completes.
             * @param future the future
             * @return an instance of this Builder
             */
            Builder<ResponseT, ResultT> resultFuture(CompletableFuture<ResultT> future);
        }
    }

    @SdkProtectedApi
    enum TransformerType {
        FILE("File", "f"),
        BYTES("Bytes", "b"),
        STREAM("Stream", "s"),
        PUBLISHER("Publisher", "p"),
        UNKNOWN("Unknown", "u");

        private static final Map<String, TransformerType> VALUE_MAP =
            EnumUtils.uniqueIndex(TransformerType.class, TransformerType::getName);

        private final String name;
        private final String shortValue;

        TransformerType(String name, String shortValue) {
            this.name = name;
            this.shortValue = shortValue;
        }

        public String getName() {
            return name;
        }

        public String getShortValue() {
            return shortValue;
        }

        public static String shortValueFromName(String name) {
            return VALUE_MAP.getOrDefault(name, UNKNOWN).getShortValue();
        }
    }
}
