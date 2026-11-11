package kr.carlog.pleos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PwaExpenseCategoryTest {
    @Test fun everyPwaExpenseCodeRoundTripsWithTheAutomotiveLabel() {
        val expected = mapOf("insurance" to "보험", "tax" to "세금", "parking" to "주차", "toll" to "통행료",
            "wash" to "세차", "fine" to "과태료", "accessory" to "용품", "etc" to "기타")
        assertEquals(expected, pwaExpenseCategories.toMap())
        expected.forEach { (code, label) ->
            assertEquals(label, appExpenseCategory(code))
            assertEquals(code, pwaExpenseCode(label))
        }
    }

    @Test fun unknownCodesAndLabelsAreNotSilentlyMappedToOther() {
        assertNull(appExpenseCategory("future_category"))
        assertNull(pwaExpenseCode("사용자 분류"))
        assertNull(pwaExpenseCode("parking"))
    }
}
