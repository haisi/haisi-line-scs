package li.selman.optimisticlocking.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.admin.bit.jeap.security.test.resource.ServletSemanticAuthorizationMock;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

class BusinessPartnerFilterTest {

    private static final String SYSTEM = "wvs";
    private static final String ACME = "acme";
    private static final String OTHER_CORP = "other-corp";

    @Test
    void noPartnerIdHeader_letsRequestThrough() throws Exception {
        BusinessPartnerFilter filter = new BusinessPartnerFilter(ServletSemanticAuthorizationMock.builder()
                .systemName(SYSTEM)
                .businessPartnerRole(ACME, Set.of())
                .build());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void partnerIdTheCallerIsAffiliatedWith_letsRequestThrough() throws Exception {
        BusinessPartnerFilter filter = new BusinessPartnerFilter(ServletSemanticAuthorizationMock.builder()
                .systemName(SYSTEM)
                .businessPartnerRole(ACME, Set.of())
                .build());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(BusinessPartnerFilter.PARTNER_ID_HEADER, ACME);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void partnerIdTheCallerIsNotAffiliatedWith_throwsAccessDenied() {
        BusinessPartnerFilter filter = new BusinessPartnerFilter(ServletSemanticAuthorizationMock.builder()
                .systemName(SYSTEM)
                .businessPartnerRole(ACME, Set.of())
                .build());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(BusinessPartnerFilter.PARTNER_ID_HEADER, OTHER_CORP);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isInstanceOf(AccessDeniedException.class);
    }
}
