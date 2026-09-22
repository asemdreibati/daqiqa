package io.daqiqa.ordering.application;

/** How one checkout attempt ends: the response its key holds, and how to count it. */
record Settlement(StoredResponse response, CheckoutOutcome outcome) {}
