package io.daqiqa.inventory.adapter.in.web;

/**
 * An identifier in the request (path, query or body) is malformed: the client's mistake, answered with 400. Other
 * {@code IllegalArgumentException}s, from deeper down, are server bugs and must not be blamed on the client.
 */
final class MalformedIdentifierException extends RuntimeException {

    MalformedIdentifierException(IllegalArgumentException cause) {
        super(cause.getMessage(), cause);
    }
}
