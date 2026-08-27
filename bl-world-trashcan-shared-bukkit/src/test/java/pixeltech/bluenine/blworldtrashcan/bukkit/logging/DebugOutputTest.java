package pixeltech.bluenine.blworldtrashcan.bukkit.logging;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 验证调试开关和延迟消息生成。 */
public final class DebugOutputTest {
    private Logger logger;
    private CapturingHandler handler;

    /** 初始化测试日志接收器。 */
    @Before
    public void setUp() {
        logger = Logger.getLogger("DebugOutputTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        handler = new CapturingHandler();
        logger.addHandler(handler);
    }

    /** 释放测试日志接收器。 */
    @After
    public void tearDown() {
        logger.removeHandler(handler);
    }

    /** 关闭时不生成消息，也不输出日志。 */
    @Test
    public void disabledDoesNotEvaluateSupplier() {
        DebugOutput output = new DebugOutput(logger);
        final boolean[] evaluated = new boolean[]{false};

        output.debug(() -> {
            evaluated[0] = true;
            return "should-not-be-evaluated";
        });

        assertFalse(output.isEnabled());
        assertFalse(evaluated[0]);
        assertEquals(0, handler.messages.size());
    }

    /** 开启后输出普通调试和追踪日志。 */
    @Test
    public void enabledWritesDebugAndTrace() {
        DebugOutput output = new DebugOutput(logger);
        output.setEnabled(true);

        output.debug(() -> "debug-message");
        output.trace(() -> "trace-message");

        assertTrue(output.isEnabled());
        assertEquals(2, handler.messages.size());
        assertEquals("debug-message", handler.messages.get(0));
        assertEquals("trace-message", handler.messages.get(1));
    }

    /** 接收日志消息的测试处理器。 */
    private static final class CapturingHandler extends Handler {
        private final List<String> messages = new ArrayList<>();

        /** 接收一条日志记录。 */
        @Override
        public void publish(LogRecord record) {
            messages.add(record.getMessage());
        }

        /** 关闭处理器。 */
        @Override
        public void flush() {
        }

        /** 关闭处理器。 */
        @Override
        public void close() {
        }
    }
}
