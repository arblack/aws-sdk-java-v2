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

package software.amazon.awssdk.protocol.runners;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.lang.reflect.InvocationTargetException;
import java.util.Base64;
import org.junit.Assert;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.codegen.model.intermediate.IntermediateModel;
import software.amazon.awssdk.codegen.model.intermediate.Metadata;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.protocol.asserts.unmarshalling.UnmarshallingTestContext;
import software.amazon.awssdk.protocol.model.GivenResponse;
import software.amazon.awssdk.protocol.model.TestCase;
import software.amazon.awssdk.protocol.model.Then;
import software.amazon.awssdk.protocol.reflect.ClientReflector;
import software.amazon.awssdk.protocol.reflect.ShapeModelReflector;
import software.amazon.awssdk.utils.IoUtils;

/**
 * Test runner for test cases exercising the client unmarshallers.
 */
class UnmarshallingTestRunner {

    private final IntermediateModel model;
    private final Metadata metadata;
    private final ClientReflector clientReflector;

    UnmarshallingTestRunner(IntermediateModel model, ClientReflector clientReflector) {
        this.model = model;
        this.metadata = model.getMetadata();
        this.clientReflector = clientReflector;
    }

    void runTest(TestCase testCase) throws Exception {
        resetWireMock(testCase.getGiven().getResponse());

        switch (testCase.getWhen().getAction()) {
            case UNMARSHALL:
                runUnmarshallTest(testCase);
                break;
            case ERROR_UNMARSHALL:
                runErrorUnmarshallTest(testCase);
                break;
            default:
                throw new IllegalArgumentException("UnmarshallingTestRunner unable to run test case for action "
                                                   + testCase.getWhen().getAction());
        }
    }

    private void runUnmarshallTest(TestCase testCase) throws Exception {
        String operationName = testCase.getWhen().getOperationName();
        ShapeModelReflector shapeModelReflector = createShapeModelReflector(testCase);
        if (!hasStreamingMember(operationName)) {
            Object actualResult = clientReflector.invokeMethod(testCase, shapeModelReflector.createShapeObject());
            testCase.getThen().getUnmarshallingAssertion().assertMatches(createContext(operationName), actualResult);
        } else {
            CapturingResponseTransformer responseHandler = new CapturingResponseTransformer();
            Object actualResult = clientReflector
                .invokeStreamingMethod(testCase, shapeModelReflector.createShapeObject(), responseHandler);
            testCase.getThen().getUnmarshallingAssertion()
                    .assertMatches(createContext(operationName, responseHandler.captured), actualResult);
        }
    }

    private void runErrorUnmarshallTest(TestCase testCase) throws Exception {
        String operationName = testCase.getWhen().getOperationName();
        ShapeModelReflector shapeModelReflector = createShapeModelReflector(testCase);
        try {
            clientReflector.invokeMethod(testCase, shapeModelReflector.createShapeObject());
            throw new IllegalStateException("Test case expected client to throw error");
        } catch (InvocationTargetException t) {
            String errorName = testCase.getWhen().getErrorName();
            Throwable cause = t.getCause();
            Then then = testCase.getThen();

            then.getErrorUnmarshallingAssertion().assertMatches(
                createErrorContext(operationName, errorName), cause);

            validateErrorCodeIfPresent(then, cause);
        }
    }

    private void validateErrorCodeIfPresent(Then then, Throwable cause) {
        String expectedErrorCode = then.getErrorCode();
        if (expectedErrorCode != null) {
            String actualErrorCode = extractErrorCode(cause);
            Assert.assertEquals(expectedErrorCode, actualErrorCode);
        }
    }

    private String extractErrorCode(Throwable cause) {
        if (!(cause instanceof AwsServiceException)) {
            return null;
        }
        AwsErrorDetails awsErrorDetails = ((AwsServiceException) cause).awsErrorDetails();
        return awsErrorDetails.errorCode();
    }

    private UnmarshallingTestContext createErrorContext(String operationName, String errorName) {
        return new UnmarshallingTestContext()
            .withModel(model)
            .withOperationName(operationName)
            .withErrorName(errorName);
    }

    /**
     * {@link ResponseTransformer} that simply captures all the content as a String so we
     * can compare it with the expected in
     * {@link software.amazon.awssdk.protocol.asserts.unmarshalling.UnmarshalledResultAssertion}.
     */
    private static class CapturingResponseTransformer implements ResponseTransformer<Object, Void> {

        private String captured;

        @Override
        public Void transform(Object response, AbortableInputStream inputStream) throws Exception {
            this.captured = IoUtils.toUtf8String(inputStream);
            return null;
        }

    }

    private boolean hasStreamingMember(String operationName) {
        return model.getShapes().get(operationName + "Response").isHasStreamingMember();
    }

    /**
     * Reset wire mock and re-configure stubbing.
     */
    private void resetWireMock(GivenResponse givenResponse) {
        WireMock.reset();
        // Stub to return given response in test definition.
        stubFor(any(urlMatching(".*")).willReturn(toResponseBuilder(givenResponse)));
    }

    private ResponseDefinitionBuilder toResponseBuilder(GivenResponse givenResponse) {

        ResponseDefinitionBuilder responseBuilder = aResponse().withStatus(200);
        if (givenResponse.getHeaders() != null) {
            givenResponse.getHeaders().forEach((key, values) -> {
                responseBuilder.withHeader(key, values.toArray(new String[0]));
            });
        }
        if (givenResponse.getStatusCode() != null) {
            responseBuilder.withStatus(givenResponse.getStatusCode());
        }
        if (givenResponse.getBody() != null) {
            responseBuilder.withBody(givenResponse.getBody());
        } else if (givenResponse.getBinaryBody() != null) {
            responseBuilder.withBody(Base64.getDecoder().decode(givenResponse.getBinaryBody()));
        } else if (metadata.isXmlProtocol()) {
            // XML Unmarshallers expect at least one level in the XML document. If no body is explicitly
            // set by the test add a fake one here.
            responseBuilder.withBody("<foo></foo>");
        }
        return responseBuilder;
    }

    private ShapeModelReflector createShapeModelReflector(TestCase testCase) {
        String operationName = testCase.getWhen().getOperationName();
        String requestClassName = getOperationRequestClassName(operationName);
        JsonNode input = testCase.getGiven().getInput();
        return new ShapeModelReflector(model, requestClassName, input);
    }

    private UnmarshallingTestContext createContext(String operationName) {
        return createContext(operationName, null);
    }

    private UnmarshallingTestContext createContext(String operationName, String streamedResponse) {
        return new UnmarshallingTestContext()
                .withModel(model)
                .withOperationName(operationName)
                .withStreamedResponse(streamedResponse);
    }

    /**
     * @return Name of the request class that corresponds to the given operation.
     */
    private String getOperationRequestClassName(String operationName) {
        return model.getOperations().get(operationName).getInput().getVariableType();
    }

}
