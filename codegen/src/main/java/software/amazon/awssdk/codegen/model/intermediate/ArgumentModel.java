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

package software.amazon.awssdk.codegen.model.intermediate;

import java.util.Objects;

public class ArgumentModel extends DocumentationModel {

    private String name;

    private String type;

    private boolean isEnumArg;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public ArgumentModel withName(String name) {
        this.name = name;
        return this;
    }

    public ArgumentModel withType(String type) {
        this.type = type;
        return this;
    }

    public boolean getIsEnumArg() {
        return isEnumArg;
    }

    public void setIsEnumArg(boolean isEnumArg) {
        this.isEnumArg = isEnumArg;
    }

    public ArgumentModel withIsEnumArg(boolean isEnumArg) {
        this.isEnumArg = isEnumArg;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        ArgumentModel that = (ArgumentModel) o;
        return isEnumArg == that.isEnumArg
               && Objects.equals(name, that.name)
               && Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + Objects.hashCode(name);
        result = 31 * result + Objects.hashCode(type);
        result = 31 * result + Boolean.hashCode(isEnumArg);
        return result;
    }
}
