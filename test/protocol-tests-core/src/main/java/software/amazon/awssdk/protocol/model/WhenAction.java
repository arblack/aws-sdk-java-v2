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

package software.amazon.awssdk.protocol.model;

public enum WhenAction {
    MARSHALL("marshall"),
    UNMARSHALL("unmarshall"),
    ERROR_UNMARSHALL("errorUnmarshall");

    private final String action;

    WhenAction(String action) {
        this.action = action;
    }

    public static WhenAction fromValue(String action) {
        switch (action) {
            case "marshall":
                return MARSHALL;
            case "unmarshall":
                return UNMARSHALL;
            case "errorUnmarshall":
                return ERROR_UNMARSHALL;
            default:
                throw new IllegalArgumentException("Unsupported test action " + action);
        }
    }
}
