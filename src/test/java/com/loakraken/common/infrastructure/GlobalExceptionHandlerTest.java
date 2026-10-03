package com.loakraken.common.infrastructure;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.BusinessException;
import com.loakraken.common.api.ErrorCode;
import com.loakraken.common.api.SuccessCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {
  private MockMvc mockMvc;

  @BeforeEach
  void 설정() {
    mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
        .setControllerAdvice(new GlobalExceptionHandler()).build();
  }

  @Test
  void 정상_응답은_code_message_data를_반환한다() throws Exception {
    mockMvc.perform(get("/test/success")).andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("TEST_SUCCESS"))
        .andExpect(jsonPath("$.message").value("조회에 성공했습니다."))
        .andExpect(jsonPath("$.data").value("result"));
  }

  @Test
  void 도메인_예외의_상태와_코드를_유지한다() throws Exception {
    mockMvc.perform(get("/test/business")).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TEST_NOT_FOUND"))
        .andExpect(jsonPath("$.data").isEmpty());
  }

  @Test
  void 필수_파라미터_누락은_400이다() throws Exception {
    mockMvc.perform(get("/test/parameter")).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void 잘못된_JSON은_400이며_입력값을_노출하지_않는다() throws Exception {
    mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("secret"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(content().string(not(containsString("secret"))));
  }

  @Test
  void 본문_Validation_실패는_400이다() throws Exception {
    mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void 허용되지_않는_메서드는_405와_Allow_헤더를_유지한다() throws Exception {
    mockMvc.perform(post("/test/success")).andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", containsString("GET")))
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
  }

  @Test
  void 지원하지_않는_본문_형식은_415이다() throws Exception {
    mockMvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("hello"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
  }

  @Test
  void 없는_경로는_404이다() throws Exception {
    mockMvc.perform(get("/missing")).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void 예상하지_못한_예외는_500이며_내부_내용을_노출하지_않는다() throws Exception {
    mockMvc.perform(get("/test/error")).andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
        .andExpect(content().string(not(containsString("secret"))));
  }

  @RestController
  static class TestController {
    @GetMapping("/test/success")
    ApiResponse<String> success() {
      return ApiResponse.of(new SuccessCode() {
        public HttpStatus httpStatus() { return HttpStatus.OK; }
        public String code() { return "TEST_SUCCESS"; }
        public String message() { return "조회에 성공했습니다."; }
      }, "result");
    }

    @GetMapping("/test/business")
    void business() {
      throw new BusinessException(new ErrorCode() {
        public HttpStatus httpStatus() { return HttpStatus.NOT_FOUND; }
        public String code() { return "TEST_NOT_FOUND"; }
        public String message() { return "대상을 찾을 수 없습니다."; }
      });
    }

    @GetMapping("/test/parameter")
    void parameter(@RequestParam String name) {}

    @PostMapping(value = "/test/body", consumes = MediaType.APPLICATION_JSON_VALUE)
    void body(@RequestBody @Valid TestRequest request) {}

    @GetMapping("/test/error")
    void error() { throw new IllegalStateException("secret"); }
  }

  record TestRequest(@NotBlank String name) {}
}
