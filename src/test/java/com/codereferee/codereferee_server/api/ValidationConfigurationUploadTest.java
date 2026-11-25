package com.codereferee.codereferee_server.api;

import com.codereferee.codereferee_server.application.RefereeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ValidationConfigurationUploadTest {
    private final RefereeService service = mock(RefereeService.class);
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new RefereeController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(service.submit(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("upload-job");
    }

    @Test
    void advertisesSupportWithoutSubmittingAJob() throws Exception {
        mvc.perform(get("/api/validations/capabilities"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.validationYaml").value(true))
                .andExpect(jsonPath("$.validationYamlMaxBytes").value(65536));
        verifyNoInteractions(service);
    }

    @Test
    void uploadedYamlIsPassedIntactAndOmissionKeepsTheExistingRoute() throws Exception {
        String yaml = "version: 1\nservice:\n  port: 8000\n";
        mvc.perform(post("/api/validations/repository").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("repository_url", "https://github.com/example/app", "validation_yaml", yaml))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.requestId").value("upload-job"));
        verify(service).submit(eq("https://github.com/example/app"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(yaml));
        mvc.perform(post("/api/validations/repository").contentType(MediaType.APPLICATION_JSON)
                .content("{\"repository_url\":\"https://github.com/example/app\"}"))
                .andExpect(status().isAccepted());
        verify(service).submit(eq("https://github.com/example/app"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void oversizedUtf8OrBlankInputNeverCreatesAJob() throws Exception {
        for (String value : new String[]{" ", "x".repeat(65537), "가".repeat(22000), "a\0b"}) {
            mvc.perform(post("/api/validations/repository").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("repository_url", "https://github.com/example/app", "validation_yaml", value))))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void profileAndUploadAreRejectedInsteadOfSilentlyChoosingOne() throws Exception {
        mvc.perform(post("/api/validations/repository").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("repository_url", "https://github.com/example/app",
                        "deployment_profile", "quickbyte-demo", "validation_yaml", "version: 1\nservice: {}\n"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
