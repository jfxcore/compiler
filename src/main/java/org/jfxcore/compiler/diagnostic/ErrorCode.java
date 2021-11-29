// Copyright (c) 2021, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license that can be found in the LICENSE file.

package org.jfxcore.compiler.diagnostic;

public enum ErrorCode {

    INTERNAL_ERROR,
    UNSUPPORTED,

    // Parser errors
    UNMATCHED_TAG,
    UNKNOWN_NAMESPACE,
    NAMESPACE_NOT_SPECIFIED,
    ELEMENT_CANNOT_START_WITH_LOWERCASE_LETTER,
    EXPECTED_TOKEN,
    EXPECTED_IDENTIFIER,
    UNEXPECTED_TOKEN,
    UNEXPECTED_END_OF_FILE,
    INVALID_EXPRESSION,

    // Symbol resolution errors
    NOT_FOUND,
    CLASS_NOT_FOUND,
    MEMBER_NOT_FOUND,
    METHOD_NOT_FOUND,
    PROPERTY_NOT_FOUND,
    INVALID_INVARIANT_REFERENCE,
    UNNAMED_PACKAGE_NOT_SUPPORTED,

    // General compiler errors
    CODEBEHIND_CLASS_NAME_MISMATCH,
    UNKNOWN_INTRINSIC,
    UNEXPECTED_INTRINSIC,
    DUPLICATE_ID,
    INVALID_ID,
    INVALID_CONTENT_IN_STYLESHEET,
    STYLESHEET_ERROR,
    CANNOT_ADD_ITEM_INCOMPATIBLE_TYPE,
    CANNOT_ADD_ITEM_INCOMPATIBLE_VALUE,
    UNSUPPORTED_MAP_KEY_TYPE,
    TYPE_ARGUMENT_OUT_OF_BOUND,
    NUM_TYPE_ARGUMENTS_MISMATCH,
    ROOT_CLASS_CANNOT_BE_FINAL,
    CANNOT_ASSIGN_FUNCTION_ARGUMENT,
    NUM_FUNCTION_ARGUMENTS_MISMATCH,
    AMBIGUOUS_METHOD_CALL,
    METHOD_NOT_STATIC,

    // Object initialization errors
    CONSTRUCTOR_NOT_FOUND,
    VALUEOF_METHOD_NOT_FOUND,
    CONFLICTING_PROPERTIES,
    CANNOT_ASSIGN_CONSTANT,
    CONSTANT_CANNOT_HAVE_CONTENT,
    CONSTANT_CANNOT_BE_MODIFIED,
    CANNOT_PARAMETERIZE_TYPE,
    BINDING_EXPRESSION_NOT_APPLICABLE,
    OBJECT_CANNOT_HAVE_MULTIPLE_CHILDREN,
    OBJECT_MUST_CONTAIN_TEXT,

    // Property assignment errors
    INCOMPATIBLE_PROPERTY_TYPE,
    CANNOT_COERCE_PROPERTY_VALUE,
    CANNOT_MODIFY_READONLY_PROPERTY,
    PROPERTY_CANNOT_BE_EMPTY,
    PROPERTY_MUST_CONTAIN_TEXT,
    PROPERTY_CANNOT_HAVE_MULTIPLE_VALUES,
    PROPERTY_MUST_BE_SPECIFIED,
    DUPLICATE_PROPERTY,
    UNSUITABLE_EVENT_HANDLER,
    INVALID_BINDING_TARGET,
    INVALID_CONTENT_BINDING_TARGET,
    INVALID_BIDIRECTIONAL_CONTENT_BINDING_TARGET,
    INVALID_CONTENT_ASSIGNMENT_TARGET,

    // Binding source errors
    SOURCE_TYPE_MISMATCH,
    CANNOT_CONVERT_SOURCE_TYPE,
    INVALID_CONTENT_ASSIGNMENT_SOURCE,
    INVALID_CONTENT_BINDING_SOURCE,
    INVALID_BIDIRECTIONAL_BINDING_SOURCE,
    INVALID_BIDIRECTIONAL_CONTENT_BINDING_SOURCE,
    EXPRESSION_NOT_INVERTIBLE,
    INVALID_BIDIRECTIONAL_METHOD_PARAM_COUNT,
    INVALID_BIDIRECTIONAL_METHOD_PARAM_KIND,
    INVALID_BINDING_EXPRESSION,
    INVALID_BINDING_CONTEXT,
    PARENT_TYPE_NOT_FOUND,
    PARENT_INDEX_OUT_OF_BOUNDS,
    CANNOT_BIND_FUNCTION,
    BINDING_NOT_SUPPORTED,
    METHOD_NOT_INVERTIBLE,
    INVALID_INVERSE_METHOD

}
