package com.feiniu.carmusic.diagnostics;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AppLogTest {
    @Test public void secretsAreRedactedFromKeyValuesUrlsAndBearerHeaders() {
        String safe = AppLog.redact("password=hunter2 token:abc url=https://host/x?sign=xyz&ok=1 Authorization=Bearer-secret Bearer real.jwt.token");
        assertFalse(safe.contains("hunter2"));
        assertFalse(safe.contains("abc"));
        assertFalse(safe.contains("xyz"));
        assertFalse(safe.contains("real.jwt.token"));
        assertTrue(safe.contains("<redacted>"));
        assertTrue(safe.contains("ok=1"));
    }
}
