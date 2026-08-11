package io.github.verybigsad.pimobile.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApprovalOfferDeadlineTest {
    @Test
    fun urgencyRampsAtCoordinatorSuppliedDeadlineThresholds() {
        assertThat(approvalUrgencyForRemainingSeconds(120)).isEqualTo(ApprovalUrgency.CALM)
        assertThat(approvalUrgencyForRemainingSeconds(61)).isEqualTo(ApprovalUrgency.CALM)
        assertThat(approvalUrgencyForRemainingSeconds(60)).isEqualTo(ApprovalUrgency.WARNING)
        assertThat(approvalUrgencyForRemainingSeconds(31)).isEqualTo(ApprovalUrgency.WARNING)
        assertThat(approvalUrgencyForRemainingSeconds(30)).isEqualTo(ApprovalUrgency.CRITICAL)
        assertThat(approvalUrgencyForRemainingSeconds(1)).isEqualTo(ApprovalUrgency.CRITICAL)
        assertThat(approvalUrgencyForRemainingSeconds(0)).isEqualTo(ApprovalUrgency.EXPIRED)
    }
}
