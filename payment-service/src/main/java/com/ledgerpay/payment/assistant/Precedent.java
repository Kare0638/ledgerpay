package com.ledgerpay.payment.assistant;

import java.util.UUID;

/**
 * An earlier analysis given with a new question as a hint (#64): the latest completed analysis of
 * another break, not overruled, with what kind of break it was.
 *
 * @param inquiryConfirmed true when a person confirmed the inquiry it proposed; false when it
 *     proposed none
 */
record Precedent(
    UUID analysisId, String breakSignature, Analysis analysis, boolean inquiryConfirmed) {}
