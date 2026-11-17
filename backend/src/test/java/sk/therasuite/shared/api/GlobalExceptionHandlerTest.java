package sk.therasuite.shared.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new DummyController())
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();

  @Test
  void validationErrorReturns400ProblemDetail() throws Exception {
    mvc.perform(post("/test").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("name"));
  }

  @Test
  void unknownPathReturns404ProblemDetail() throws Exception {
    mvc.perform(get("/neexistuje"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void optimisticLockReturns409() throws Exception {
    mvc.perform(get("/conflict"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"));
  }

  @Test
  void apiExceptionExtensionsAreAddedToProblemDetail() throws Exception {
    mvc.perform(get("/confirm"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFIRMATION_REQUIRED"))
        .andExpect(jsonPath("$.confirmable").value(true))
        .andExpect(jsonPath("$.warnings[0]").value("X"));
  }

  @RestController
  static class DummyController {
    @GetMapping("/confirm")
    void confirm() {
      throw new sk.therasuite.shared.application.ApiException(
          org.springframework.http.HttpStatus.CONFLICT,
          "CONFIRMATION_REQUIRED",
          "Potvrďte.",
          java.util.Map.of("confirmable", true, "warnings", java.util.List.of("X")));
    }

    record Body(@NotBlank String name) {}

    @PostMapping("/test")
    void create(@Valid @RequestBody Body body) {}

    @GetMapping("/conflict")
    void conflict() {
      throw new OptimisticLockingFailureException("x");
    }
  }
}
