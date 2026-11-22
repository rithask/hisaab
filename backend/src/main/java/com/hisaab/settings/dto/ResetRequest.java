package com.hisaab.settings.dto;

import com.hisaab.settings.model.ResetPart;
import java.util.List;

/** @param word what I typed to confirm ("reset") */
public record ResetRequest(List<ResetPart> parts, String word, boolean withoutBackup) {}
