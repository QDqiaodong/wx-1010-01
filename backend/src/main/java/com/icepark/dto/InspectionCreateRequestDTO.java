package com.icepark.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工作人员现场提交送检单。
 * forceTransfer：器材已发给游客时，true 表示把器材先按当前流水"转入待处理"收回再送检；
 * false（默认）则后端拒绝并提示先归还或勾选转入待处理，避免出现悬空流水。
 */
@Data
public class InspectionCreateRequestDTO {

    @NotNull(message = "器材ID不能为空")
    private Long equipmentId;

    @NotBlank(message = "发现人不能为空")
    @Size(max = 100, message = "发现人长度不能超过100")
    private String reporter;

    @NotBlank(message = "问题描述不能为空")
    @Size(max = 1000, message = "问题描述长度不能超过1000")
    private String problemDescription;

    private Boolean forceTransfer = Boolean.FALSE;
}
