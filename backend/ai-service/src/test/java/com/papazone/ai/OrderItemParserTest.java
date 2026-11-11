package com.papazone.ai;

import com.papazone.ai.model.OrderItemParser;
import com.papazone.ai.model.OrderLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderItemParserTest {

    @Test
    void parsesLegacyJavaToStringFormat() {
        String legacy = "[{id=3, customerId=1, menuItemId=6, name=Coke, unitPrice=59.0, quantity=2}, "
                + "{id=4, customerId=1, menuItemId=1, name=Margherita Pizza, unitPrice=299.0, quantity=1}]";

        List<OrderLine> lines = OrderItemParser.parse(legacy);

        assertEquals(2, lines.size());
        assertEquals("Coke", lines.get(0).name());
        assertEquals(6L, lines.get(0).menuItemId());
        assertEquals(2, lines.get(0).quantity());
        assertEquals(299.0, lines.get(1).unitPrice(), 0.0001);
    }

    @Test
    void legacyNamesMayContainCommas() {
        String legacy = "[{id=1, customerId=1, menuItemId=9, name=Pizza, Large, unitPrice=500.0, quantity=1}]";

        assertEquals("Pizza, Large", OrderItemParser.parse(legacy).get(0).name());
    }

    @Test
    void parsesRealJson() {
        String json = "[{\"menuItemId\":2,\"name\":\"Farmhouse Pizza\",\"unitPrice\":449.0,\"quantity\":3}]";

        List<OrderLine> lines = OrderItemParser.parse(json);

        assertEquals(1, lines.size());
        assertEquals(2L, lines.get(0).menuItemId());
        assertEquals(3, lines.get(0).quantity());
    }

    @Test
    void blankNullAndGarbageGiveEmptyList() {
        assertTrue(OrderItemParser.parse(null).isEmpty());
        assertTrue(OrderItemParser.parse("   ").isEmpty());
        assertTrue(OrderItemParser.parse("not an order").isEmpty());
    }
}
