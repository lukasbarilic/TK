package sk.therasuite.settings.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.settings.application.ClientConfigService;
import sk.therasuite.settings.application.ClientConfigService.ClientConfig;

/** Konfigurácia klienta; stačí prihlásený používateľ (bez osobitného oprávnenia). */
@RestController
@RequestMapping("/api/client-config")
public class ClientConfigController {

  private final ClientConfigService service;

  public ClientConfigController(ClientConfigService service) {
    this.service = service;
  }

  @GetMapping
  public ClientConfig config() {
    return service.config();
  }
}
