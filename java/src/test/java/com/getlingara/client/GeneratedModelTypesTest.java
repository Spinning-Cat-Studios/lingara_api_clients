package com.getlingara.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.getlingara.client.model.AllowanceRow;
import com.getlingara.client.model.LessonPlan;
import com.getlingara.client.model.UsageLedger;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class GeneratedModelTypesTest {
  /**
   * 29.9.26r AC32: a named uint64 field of a generated model is declared BigInteger, a uint32 field
   * Long, and a date-time and a uuid field String. uint8 is left out on purpose: its target,
   * Integer, is also openapi-generator's fallback for an unknown format, so a wrong key there
   * cannot be observed.
   */
  @Test
  void formatsMapToTheirDeclaredTypes() throws Exception {
    assertEquals(BigInteger.class, UsageLedger.class.getDeclaredField("calls").getType());
    assertEquals(Long.class, AllowanceRow.class.getDeclaredField("limit").getType());
    assertEquals(String.class, LessonPlan.class.getDeclaredField("createdAt").getType());
    assertEquals(String.class, LessonPlan.class.getDeclaredField("id").getType());
  }
}
