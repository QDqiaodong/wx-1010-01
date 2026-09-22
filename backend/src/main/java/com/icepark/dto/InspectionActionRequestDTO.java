package com.icepark.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 维修侧处理送检单：退回补充材料 / 提交复检 / 复检结论 / 报废，统一带处理人与说明。
 */
@Data
public class InspectionActionRequestDTO {

    @NotBlank(message = "处理人不能为空")
    @Size(max = 100, message = "处理人长度不能超过100")
    private String handler;

    /** 补充材料要求 / 复检意见 / 报废理由等，会写入操作痕迹 */
    @Size(max = 1000, message = "处理说明长度不能超过1000")
    private String note;

    /** 复检不通过时是否允许直接报废（false=退回维修），仅 reinspect/fail 路径使用 */
    private Boolean scrapOnFail = Boolean.FALSE;
}
