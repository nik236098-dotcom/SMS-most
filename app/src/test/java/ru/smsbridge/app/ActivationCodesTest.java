package ru.smsbridge.app;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ActivationCodesTest {
    static final String CODE = "LPA:1$smdp.example.test$SYNTHETIC-NON-ACTIVATABLE-TEST-0123456789";

    @Test public void plainAndLoosePrefixesBecomeOneCode() {
        assertEquals(CODE, ActivationCodes.parse("  " + CODE + "\n").get(0).code);
        assertEquals(CODE, ActivationCodes.parse(CODE.substring(4)).get(0).code);
        assertEquals("LPA:" + CODE.substring(4).toLowerCase(), ActivationCodes.parse(CODE.toLowerCase()).get(0).code);
    }

    @Test public void androidProvisioningUrlIsUnwrapped() {
        String url = "https://esimsetup.android.com/esim_qrcode_provisioning?carddata=" + CODE.replace(":", "%3A").replace("$", "%24");
        List<ActivationCodes.Item> items = ActivationCodes.parse(url);
        assertEquals(1, items.size());
        assertEquals(CODE, items.get(0).code);
        assertEquals("", items.get(0).number);
    }

    @Test public void ordinaryLinkIsNotAnInstallCode() {
        assertTrue(ActivationCodes.parse("https://example.test").isEmpty());
    }

    @Test public void massLinesKeepTheirOwnNumbersAndDropDupes() {
        String blob = "+7 900 000-11-11 " + CODE + "\n"
            + "8 (900) 000-22-22\nLPA:1$smdp.example.test$SECOND-CODE-0123456789\n"
            + CODE + "\n";
        List<ActivationCodes.Item> items = ActivationCodes.parse(blob);
        assertEquals(2, items.size());
        assertEquals("+79000001111", items.get(0).number);
        assertEquals(CODE, items.get(0).code);
        assertEquals("+79000002222", items.get(1).number);
        assertTrue(items.get(1).code.endsWith("SECOND-CODE-0123456789"));
    }
}
