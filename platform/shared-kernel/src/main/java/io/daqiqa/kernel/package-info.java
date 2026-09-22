/**
 * Shared kernel: the small set of value objects every bounded context agrees on.
 *
 * <p>Rules: immutable records, validated on construction, no framework or I/O dependencies. Anything that is not
 * needed by at least two contexts belongs in a context, not here.
 */
@NullMarked
package io.daqiqa.kernel;

import org.jspecify.annotations.NullMarked;
