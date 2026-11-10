package com.hisaab.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the React app for client-side routes (anything without a dot that isn't /api). */
@Controller
public class SpaController {
    // App routes are at most two segments deep (/reports, /people/12, /import/3). Paths with a dot are files, and
    // /api, /assets and the OIDC endpoints are never app routes, so they must not be answered with index.html.
    @GetMapping({"/", "/{a:^(?!api$|assets$|oauth2$|login$)[^.]+}", "/{a:^(?!api$|assets$|oauth2$|login$)[^.]+}/{b:[^.]+}"})
    public String index() {
        return "forward:/index.html";
    }
}
