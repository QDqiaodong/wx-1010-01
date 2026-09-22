package com.icepark.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 维修人员操作送检单的通用请求（退回补充材料/提交复检/判定报废/复检结论/转入待处理） */
@Data
public class InspectionActionRequestDTO {

    @NotBlank(message = "处理人不能为空")
    private String handler;

    /** 退回原因 / 补充材料 / 复检说明 / 不通过原因 / 报废原因 */
    private String remark;
}
