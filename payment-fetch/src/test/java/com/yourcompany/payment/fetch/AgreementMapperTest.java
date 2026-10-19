package com.yourcompany.payment.fetch;

import com.yourcompany.payment.fetch.config.FetchProperties;
import com.yourcompany.payment.fetch.dto.FetchDtos;
import com.yourcompany.payment.fetch.service.AgreementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dues arithmetic ported from the legacy getGoldDueDetails. These tests exist because
 * every rule in here decides how much money a borrower is shown, and none of them is
 * obvious from reading the code.
 */
class AgreementMapperTest {

    private final ObjectMapper json = new ObjectMapper();
    private AgreementMapper mapper;

    @BeforeEach
    void setUp() {
        FetchProperties props = new FetchProperties();
        props.setSeizedSubStatuses(Set.of("SEIZED", "REPO"));
        mapper = new AgreementMapper(props);
    }

    private JsonNode array(String body) {
        return json.readTree(body);
    }

    @Test
    void sumsFiguresAcrossEveryAccountNotJustTheFirst() {
        // One customer, two gold-loan accounts. The borrower pays one total.
        JsonNode in = array("""
            [ {"accountnumber":"GL1","accountStatus":"C","currentdue":1000,
               "penalOverdue":100,"otherOverdue":50,"mobilenumber":"9876543210"},
              {"accountnumber":"GL1","accountStatus":"C","currentdue":2500,
               "penalOverdue":200,"otherOverdue":25} ]""");

        FetchDtos.Dues dues = mapper.map(in, "GL1").dues();

        assertEquals(new BigDecimal("3500"), dues.getCurrentDue());
        assertEquals(new BigDecimal("300"), dues.getPenalOverdue());
        assertEquals(new BigDecimal("75"), dues.getOtherOverdue());
        assertEquals(2, dues.getAgreementCount());
    }

    @Test
    void subtractsVisitChargesFromCurrentDueAndOtherOverdue() {
        // The LMS includes visit charges in both totals; this platform does not collect
        // them, so leaving them in overcharges every borrower who has one.
        JsonNode in = array("""
            [ {"accountnumber":"GL1","accountStatus":"C","currentdue":1000,"otherOverdue":400,
               "chargeDueDetails":[{"chargeCode":"CCVCHG","chargeDue":150}]} ]""");

        FetchDtos.Dues dues = mapper.map(in, "GL1").dues();

        assertEquals(new BigDecimal("850"), dues.getCurrentDue());
        assertEquals(new BigDecimal("250"), dues.getOtherOverdue());
        // Not shown to the caller: we are not collecting it.
        assertTrue(dues.getCharges().stream().noneMatch(c -> "CCVCHG".equals(c.getChargeCode())));
    }

    @Test
    void otherChargesAreReturnedToTheCaller() {
        JsonNode in = array("""
            [ {"accountnumber":"GL1","accountStatus":"C","currentdue":1000,
               "chargeDueDetails":[{"chargeCode":"LATEFEE","chargeName":"Late fee","chargeDue":75}]} ]""");

        FetchDtos.Dues dues = mapper.map(in, "GL1").dues();

        assertEquals(1, dues.getCharges().size());
        assertEquals("LATEFEE", dues.getCharges().get(0).getChargeCode());
        assertEquals(new BigDecimal("75"), dues.getCharges().get(0).getChargeDue());
        // Charges are reported, not deducted — only visit charges come out of the totals.
        assertEquals(new BigDecimal("1000"), dues.getCurrentDue());
    }

    @Test
    void lossAmountReplacesCurrentDueAndChangesTheStatus() {
        // A loss amount on a non-'L' account: status becomes LOSSAMT and the loss amount
        // IS what is collectable — it replaces the current due rather than adding to it.
        JsonNode in = array("""
            [ {"accountnumber":"GL1","accountStatus":"C","currentdue":1000,"lossamount":7500} ]""");

        var mapped = mapper.map(in, "GL1");

        assertEquals(new BigDecimal("7500"), mapped.dues().getCurrentDue());
        assertEquals("LOSSAMT", mapped.customer().getAccountStatus());
    }

    @Test
    void anAccountAlreadyWrittenOffKeepsStatusLAndContributesNoLossAmount() {
        JsonNode in = array("""
            [ {"accountnumber":"GL1","accountStatus":"L","currentdue":1000,"lossamount":7500} ]""");

        var mapped = mapper.map(in, "GL1");

        assertEquals("L", mapped.customer().getAccountStatus());
        // lossamount on an 'L' account is excluded, so the current due stands.
        assertEquals(new BigDecimal("1000"), mapped.dues().getCurrentDue());
    }

    @Test
    void anyAccountInLossMakesTheCustomerStatusL() {
        JsonNode in = array("""
            [ {"accountnumber":"GL1","accountStatus":"C","currentdue":500},
              {"accountnumber":"GL1","accountStatus":"L","currentdue":500} ]""");

        assertEquals("L", mapper.map(in, "GL1").customer().getAccountStatus());
    }

    @Test
    void aSeizedSubStatusOverridesEverything() {
        JsonNode in = array("""
            [ {"accountnumber":"GL1","accountStatus":"C","currentdue":1000,
               "agreementSubStatus":"SEIZED"} ]""");

        assertEquals("SR", mapper.map(in, "GL1").customer().getAccountStatus());
    }

    @Test
    void aNegativeTotalIsClampedToZero() {
        // A visit charge larger than the due it is subtracted from. Never show a borrower
        // a negative amount payable, and never let one reach a vendor.
        JsonNode in = array("""
            [ {"accountnumber":"GL1","accountStatus":"C","currentdue":100,
               "chargeDueDetails":[{"chargeCode":"CCVCHGCD","chargeDue":500}]} ]""");

        assertEquals(BigDecimal.ZERO, mapper.map(in, "GL1").dues().getCurrentDue());
    }

    @Test
    void amountsKeepTheirPaiseAndDoNotDriftThroughDouble() {
        JsonNode in = array("""
            [ {"accountnumber":"GL1","accountStatus":"C","currentdue":"0.10"},
              {"accountnumber":"GL1","accountStatus":"C","currentdue":"0.20"} ]""");

        // 0.1 + 0.2 as doubles is 0.30000000000000004.
        assertEquals(0, mapper.map(in, "GL1").dues().getCurrentDue().compareTo(new BigDecimal("0.30")));
    }

    @Test
    void theMobileNumberIsAlwaysMasked() {
        JsonNode in = array("""
            [ {"accountnumber":"GL1","accountStatus":"C","mobilenumber":"9876543210"} ]""");

        String masked = mapper.map(in, "GL1").customer().getMaskedMobile();
        assertTrue(masked == null || !masked.contains("9876543210"),
                "the full mobile number must never leave this service");
    }

    @Test
    void prefersTheVerifiedAddressWhenAddrefidMatches() {
        JsonNode in = array("""
            [ {"accountnumber":"GL1","accountStatus":"C",
               "addressverify":[{"addrefid":"A2"}],
               "addressdetails":[
                 {"addrefid":"A1","addressType":"Office Address","addressLineOne":"Office St","city":"Chennai"},
                 {"addrefid":"A2","addressType":"Residence Address","addressLineOne":"Home St","city":"Madurai"}]} ]""");

        FetchDtos.Address address = mapper.map(in, "GL1").customer().getAddress();

        assertEquals("Home St", address.getLine1());
        assertEquals("Madurai", address.getCity());
    }

    @Test
    void fallsBackToTheResidenceAddressWhenNothingIsVerified() {
        JsonNode in = array("""
            [ {"accountnumber":"GL1","accountStatus":"C",
               "addressdetails":[
                 {"addressType":"Office Address","addressLineOne":"Office St"},
                 {"addressType":"Residence Address","addressLineOne":"Home St"}]} ]""");

        assertEquals("Home St", mapper.map(in, "GL1").customer().getAddress().getLine1());
    }

    @Test
    void survivesAPayloadWithNoAddressAtAll() {
        JsonNode in = array("""
            [ {"accountnumber":"GL1","accountStatus":"C","currentdue":1000} ]""");

        assertNull(mapper.map(in, "GL1").customer().getAddress());
    }

    @Test
    void missingAndNonNumericFieldsBecomeZeroRatherThanFailing() {
        JsonNode in = array("""
            [ {"accountnumber":"GL1","accountStatus":"C","currentdue":"not-a-number"} ]""");

        assertEquals(BigDecimal.ZERO, mapper.map(in, "GL1").dues().getCurrentDue());
        assertEquals(BigDecimal.ZERO, mapper.map(in, "GL1").dues().getPenalOverdue());
    }

    @Test
    void usesTheRequestedAgreementWhenTheLmsReturnsNone() {
        JsonNode in = array("""
            [ {"accountStatus":"C","currentdue":1000} ]""");

        assertEquals("GL0001234567", mapper.map(in, "GL0001234567").customer().getAgreementNumber());
    }
}
