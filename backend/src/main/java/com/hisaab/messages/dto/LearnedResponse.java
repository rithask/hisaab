package com.hisaab.messages.dto;

import java.util.List;

/**
 * @param senders        SMS sender ids whose bank the app learned
 * @param trustedDomains email domains I trusted myself
 * @param bankDomains    the banks' own email domains, built in
 */
public record LearnedResponse(List<SmsTemplateResponse> formats, List<PayeeAliasResponse> payees, long senders, List<TrustedDomainResponse> trustedDomains,
                              List<BankDomainsResponse> bankDomains) {}
