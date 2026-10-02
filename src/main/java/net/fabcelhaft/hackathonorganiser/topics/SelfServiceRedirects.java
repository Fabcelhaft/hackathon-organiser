package net.fabcelhaft.hackathonorganiser.topics;

import java.util.regex.Pattern;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The one allow-list of screens a self-service Topic action may return the participant to
 * (feature 013 FR-011b; contracts/vote-control-fragment.md).
 *
 * <p>Both the upvote actions and the Join action let the submitting page name where it wants to
 * come back to, so that acting on a row does not move the participant somewhere else. That value
 * arrives from the browser and is therefore attacker-controlled, so it is never reflected
 * verbatim — only a value matching this allow-list is used, and anything else falls back to the
 * Home Page.
 *
 * <p>This lives in one class rather than one per controller deliberately. Two copies of an
 * allow-list is how one of them eventually gains an entry the other lacks, and an open redirect is
 * exactly the kind of defect that survives review because each copy looks reasonable alone.
 */
final class SelfServiceRedirects {

    private static final Pattern TOPIC_DETAIL_PATH = Pattern.compile("^/topics/[0-9a-fA-F-]{36}$");

    /** Where a submission goes when it names no target, or names one that is not allowed. */
    static final String DEFAULT = "/";

    private SelfServiceRedirects() {}

    /** Reads the {@code redirect} form field, absent meaning the empty string. */
    static Mono<String> submittedValue(ServerWebExchange exchange) {
        return exchange.getFormData().map(form -> {
            String redirect = form.getFirst("redirect");
            return redirect == null ? "" : redirect;
        });
    }

    /** The submitted value if it is allowed, otherwise {@link #DEFAULT}. */
    static String safe(String redirect) {
        return isAllowed(redirect) ? redirect : DEFAULT;
    }

    static boolean isAllowed(String redirect) {
        return redirect != null
                && (DEFAULT.equals(redirect)
                        || "/topics/overview".equals(redirect)
                        || TOPIC_DETAIL_PATH.matcher(redirect).matches());
    }
}
