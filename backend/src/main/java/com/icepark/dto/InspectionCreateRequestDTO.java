package com.icepark.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 现场工作人员登记送检。
 * 若器材当前已发给游客未归还：单据进入"待归还"，游客照常归还即可；
 * 确认游客无法归还时可再调用转入待处理接口。
 */
@Data
public class InspectionCreateRequestDTO {

    @NotBlank(message = "问题描述不能为空")
    private String problemDescription;

    @NotBlank(message = "发现人不能为空")
    private String reporter;
}
