package com.example.toolecommerrce.dto.request;

import com.example.toolecommerrce.entity.ScrapeJob;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ScrapeRequest {

    private ScrapeJob.InputType inputType;

    @NotBlank(message = "INPUT_VALUE_BLANK")
    private String inputValue;

    @Min(value = 1, message = "MAX_PRODUCTS_INVALID")
    private int maxProducts = 10;
}
