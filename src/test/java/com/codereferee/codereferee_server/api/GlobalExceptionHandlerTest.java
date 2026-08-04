package com.codereferee.codereferee_server.api;

import com.codereferee.codereferee_server.application.RefereeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RefereeController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RefereeService refereeService;

    @Test
    void missingRepositoryUrlReturns400WithErrorFormat() throws Exception {
        mockMvc.perform(post("/api/validations/repository")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branch\": \"main\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").exists());
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/validations/repository")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{broken json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void missingHistoryParamsReturns400() throws Exception {
        mockMvc.perform(get("/api/validations/history"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_PARAMETER"));
    }

    @Test
    void unexpectedErrorReturns500WithoutInternalDetails() throws Exception {
        given(refereeService.submit(any(), any(), any()))
                .willThrow(new RuntimeException("connection to db failed at 10.0.3.7:5432"));

        mockMvc.perform(post("/api/validations/repository")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repository_url\": \"https://github.com/phdcoco/QuickByte_Demo\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                // 내부 정보(DB 주소 등)가 응답에 새지 않는지가 핵심 검증
                .andExpect(jsonPath("$.error.message").value("서버 내부 오류가 발생했습니다."));
    }
}