/**
 * Tests of what the assignment explicitly asks for, written as given / when / then:
 * <ul>
 *   <li>{@link com.se.telecomalarm.acceptance.AlarmIngestionAcceptanceTests} and
 *       {@link com.se.telecomalarm.acceptance.NotificationAcceptanceTests}: the acceptance checks of section 7, one test
 *       per check;</li>
 *   <li>{@link com.se.telecomalarm.acceptance.DuplicateInsertRaceTests}: sections 2 and 3, "the eventId must be globally
 *       unique" and "concurrent requests cannot corrupt the state", at the one step where two transactions meet on
 *       the same eventId; the race is reproduced deterministically through {@code pg_stat_activity}.</li>
 * </ul>
 * The author read and checked these tests line by line. The rest of the test code was generated with an AI assistant
 * from the test-case list (README, "Use of AI tools and external solutions"). All tests run in the same Spring
 * context as the tests of the {@code api} package.
 */
package com.se.telecomalarm.acceptance;
