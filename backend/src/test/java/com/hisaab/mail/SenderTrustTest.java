package com.hisaab.mail;

import com.hisaab.domain.Enums.Institution;
import com.hisaab.repo.TrustedSenderRepo;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

class SenderTrustTest {
    private static final String FROM = "alerts@hdfcbank.bank.in";

    @Test
    void dmarcDecidesWhenPresent() {
        assertEquals(SenderTrust.PASS, SenderTrust.check("mx.google.com; dkim=pass header.i=@hdfcbank.bank.in header.s=s1; spf=pass smtp.mailfrom=bounce.hdfcbank.bank.in; dmarc=pass (p=REJECT) header.from=hdfcbank.bank.in", FROM));
        // a forged bank address: DMARC fails even though some other domain's signature is fine
        assertEquals(SenderTrust.FAIL, SenderTrust.check("mx.google.com; dkim=pass header.i=@evil.example; spf=pass smtp.mailfrom=evil.example; dmarc=fail (p=REJECT) header.from=hdfcbank.bank.in", FROM));
    }

    @Test
    void withoutDmarcAnAlignedSignatureOrSpfPasses() {
        assertEquals(SenderTrust.PASS, SenderTrust.check("icloud.com; dkim=pass header.d=mail.hdfcbank.bank.in", FROM));
        assertEquals(SenderTrust.PASS, SenderTrust.check("icloud.com; spf=pass smtp.mailfrom=alerts@hdfcbank.bank.in", FROM));
        assertEquals(SenderTrust.NONE, SenderTrust.check("icloud.com; dkim=pass header.d=sendgrid.net; spf=softfail", FROM));
        assertEquals(SenderTrust.NONE, SenderTrust.check(null, FROM));
        assertEquals(SenderTrust.NONE, SenderTrust.check("mx.example; dkim=pass header.d=hdfcbank.bank.in.evil.example", FROM));
    }

    @Test
    void foldedHeaderIsRead() {
        assertEquals(SenderTrust.PASS, SenderTrust.check("mx.google.com;\r\n       dkim=pass header.i=@axis.bank.in;\r\n       dmarc=pass header.from=axis.bank.in", "alerts@axis.bank.in"));
    }

    @Test
    void onlyTheBanksDomainsOrTrustedOnesAreAccepted() {
        TrustedSenderRepo repo = Mockito.mock(TrustedSenderRepo.class);
        SenderTrust t = new SenderTrust(repo);
        assertTrue(t.judge("alerts@hdfcbank.bank.in", SenderTrust.NONE, Institution.HDFC, "HDFC Bank").ok());
        assertTrue(t.judge("Alerts@InstaAlerts.HDFCBank.net", SenderTrust.PASS, Institution.HDFC, "HDFC Bank").ok());
        var lookalike = t.judge("alerts@hdfcbank.net.evil.example", SenderTrust.PASS, Institution.HDFC, "HDFC Bank");
        assertFalse(lookalike.ok());
        assertEquals(SenderTrust.UNKNOWN_SENDER, lookalike.hold());
        // an Axis domain doesn't vouch for an HDFC alert
        assertFalse(t.judge("alerts@axis.bank.in", SenderTrust.PASS, Institution.HDFC, "HDFC Bank").ok());
        // a forged bank address is held even though the domain is right
        var forged = t.judge("alerts@hdfcbank.bank.in", SenderTrust.FAIL, Institution.HDFC, "HDFC Bank");
        assertEquals(SenderTrust.UNVERIFIED_SENDER, forged.hold());

        Mockito.when(repo.existsById("mybank-alerts.example")).thenReturn(true);
        assertTrue(t.judge("noreply@mail.mybank-alerts.example", SenderTrust.NONE, Institution.OTHER, "bank").ok());
        assertFalse(t.judge("noreply@mail.mybank-alerts.example", SenderTrust.FAIL, Institution.OTHER, "bank").ok());
    }
}
