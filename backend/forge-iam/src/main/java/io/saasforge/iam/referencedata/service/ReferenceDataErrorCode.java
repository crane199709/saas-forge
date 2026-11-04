package io.saasforge.iam.referencedata.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum ReferenceDataErrorCode implements ErrorCode {
    /** 当前清单中没有该服务的这个字典。 */
    DICTIONARY_NOT_FOUND("referenceData.dictionaryNotFound", 404),
    /** 固定字典对租户只读。 */
    DICTIONARY_READ_ONLY("referenceData.dictionaryReadOnly", 409),
    /** 字典中没有该项，或该项已下线（服务新版本删除的内置项）。 */
    ITEM_NOT_FOUND("referenceData.itemNotFound", 404),
    /** 本租户该字典已有同编码的自定义项（含已停用的，编码不得重新使用）。 */
    ITEM_CODE_TAKEN("referenceData.itemCodeTaken", 409),
    /** 与该字典现有的可选项重名。 */
    ITEM_NAME_TAKEN("referenceData.itemNameTaken", 409),
    /** 当前清单中没有该服务的这个参数。 */
    PARAMETER_NOT_FOUND("referenceData.parameterNotFound", 404),
    /** 参数未声明为租户可修改。 */
    PARAMETER_NOT_EDITABLE("referenceData.parameterNotEditable", 409),
    /** 覆盖值不满足参数声明的类型或约束，或不是字典的可选项。 */
    INVALID_PARAMETER_VALUE("referenceData.invalidParameterValue", 400);

    private final String code;
    private final int httpStatus;

    ReferenceDataErrorCode(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
