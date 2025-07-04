package in.xammer.aws_cost_api.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RootController {

    @GetMapping("/")
    public String redirectToBilling() {
        return "redirect:/billing.html";
    }
}
