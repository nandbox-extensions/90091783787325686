package com.nandbox.extension;

import com.nandbox.bots.api.Nandbox;
import com.nandbox.bots.api.NandboxClient;
import com.nandbox.bots.api.data.*;
import com.nandbox.bots.api.inmessages.*;
import com.nandbox.bots.api.outmessages.*;
import com.nandbox.bots.api.util.*;
import com.nandbox.extension.ExtensionAdapter;

import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;

public class ExtensionCustomLogic extends ExtensionAdapter {

    private Nandbox.Api api;

    private static class CalcState {
        String display;         // what to show
        String current;         // current number being entered (string form)
        BigDecimal acc;         // accumulator / left operand
        String op;              // pending operator: + - x /
        boolean error;
        boolean justEvaluated;  // true immediately after '='

        CalcState() {
            this.display = "0";
            this.current = "";
            this.acc = null;
            this.op = null;
            this.error = false;
            this.justEvaluated = false;
        }

        @Override
        public String toString() {
            return "CalcState{" +
                    "display='" + display + '\'' +
                    ", current='" + current + '\'' +
                    ", acc=" + acc +
                    ", op='" + op + '\'' +
                    ", error=" + error +
                    ", justEvaluated=" + justEvaluated +
                    '}';
        }
    }

    private final Map /* <String, CalcState> */ stateByUser = new HashMap();

    public static void main(String[] args) throws Exception {
        String TOKEN = "";
        Properties properties = new Properties();
        FileInputStream input = null;

        try {
            input = new FileInputStream("config.properties");
            properties.load(input);
            TOKEN = properties.getProperty("Token");
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                }
            }
        }

        NandboxClient client = NandboxClient.get();
        client.connect(TOKEN, new ExtensionCustomLogic());
    }

    @Override
    public void onConnect(Nandbox.Api api) {
        this.api = api;
    }

    @Override
    public void onMenuCallBack(MenuCallback menuCallback) {
        if (menuCallback == null) {
            return;
        }
        if (menuCallback.getUser() == null) {
            return;
        }
        List cells = menuCallback.getCells();
        if (cells == null || cells.isEmpty()) {
            return;
        }

        String userId = menuCallback.getUser().getId();
        String appId = menuCallback.getApp_id();
        String menuId = menuCallback.getMenu_id();

        if (menuId == null || !"dXNU7K3mDKkhECn".equals(menuId)) {
            return;
        }

        String callback = findFirstCallback(cells);
        if (callback == null) {
            return;
        }

        CalcState st = (CalcState) stateByUser.get(userId);
        if (st == null) {
            st = new CalcState();
            stateByUser.put(userId, st);
        }

        log("onMenuCallBack", "userId=" + userId + ", menuId=" + menuId + ", callback=" + callback + ", before=" + st);

        applyInput(st, callback);

        log("onMenuCallBack", "after=" + st);

        JSONArray updatedCells = new JSONArray();
        JSONObject outCell = new JSONObject();
        outCell.put("callback", "output");
        outCell.put("form", "output");
        outCell.put("headline", st.display);
        outCell.put("subhead", "");
        updatedCells.add(outCell);

        api.updateMenuCell(userId, menuId, appId, updatedCells, Utils.getUniqueId(), Boolean.FALSE);
    }

    private String findFirstCallback(List cells) {
        for (int i = 0; i < cells.size(); i++) {
            MenuCallback.Cell cell = (MenuCallback.Cell) cells.get(i);
            if (cell != null && cell.getCallback() != null && cell.getCallback().length() > 0) {
                return cell.getCallback();
            }
        }
        return null;
    }

    private void applyInput(CalcState st, String cb) {
        log("applyInput", "cb=" + cb + ", state(before)=" + st);

        // Added Reset button support: callback may be "C" or "reset" depending on menu.
        if ("C".equals(cb) || "reset".equals(cb) || "Reset".equals(cb) || "clear".equals(cb)) {
            resetState(st);
            log("applyInput", "reset pressed, state(after)=" + st);
            return;
        }

        if (st.error) {
            // reset after error on next press
            resetState(st);
            log("applyInput", "state cleared after error");
        }

        if (isDigit(cb)) {
            if (st.justEvaluated) {
                // start new calculation when typing after '='
                resetState(st);
                st.justEvaluated = false;
            }

            if (st.current == null || st.current.length() == 0) {
                st.current = cb;
            } else if ("0".equals(st.current)) {
                st.current = cb;
            } else {
                st.current = st.current + cb;
            }
            st.display = st.current;
            log("applyInput", "digit => state(after)=" + st);
            return;
        }

        if (".".equals(cb)) {
            if (st.justEvaluated) {
                resetState(st);
                st.justEvaluated = false;
            }

            if (st.current == null || st.current.length() == 0) {
                st.current = "0.";
            } else if (st.current.indexOf('.') < 0) {
                st.current = st.current + ".";
            }
            st.display = st.current;
            log("applyInput", "dot => state(after)=" + st);
            return;
        }

        if (isOperator(cb)) {
            // If user presses operator right after '=', keep accumulator and start chaining.
            st.justEvaluated = false;

            // Fold current into accumulator (if any)
            if (st.current != null && st.current.length() > 0) {
                BigDecimal cur = parseBigDecimalSafe(st.current);
                if (cur == null) {
                    setError(st, "Invalid number");
                    return;
                }

                if (st.acc == null) {
                    st.acc = cur;
                } else if (st.op != null) {
                    BigDecimal res = eval(st.acc, cur, st.op);
                    if (res == null) {
                        setError(st, "Math error");
                        return;
                    }
                    st.acc = res;
                } else {
                    st.acc = cur;
                }

                st.current = "";
            } else {
                // No current digits. If no accumulator, assume 0 as left operand.
                if (st.acc == null) {
                    st.acc = BigDecimal.ZERO;
                }
            }

            st.op = cb;
            st.display = format(st.acc);
            log("applyInput", "operator => state(after)=" + st);
            return;
        }

        if ("=".equals(cb)) {
            // Important: '=' should never invent a number or show '1' by default.
            // It must evaluate only when we have acc, op, and a right operand.

            if (st.acc == null && (st.current == null || st.current.length() == 0)) {
                st.display = "0";
                st.current = "";
                st.op = null;
                st.justEvaluated = true;
                log("applyInput", "equal with nothing => state(after)=" + st);
                return;
            }

            // If there is no pending operator, just finalize current into display and clear current.
            if (st.op == null) {
                if (st.current != null && st.current.length() > 0) {
                    st.display = st.current;
                } else if (st.acc != null) {
                    st.display = format(st.acc);
                } else {
                    st.display = "0";
                }
                st.current = ""; // clear after '=' as requested
                st.justEvaluated = true;
                log("applyInput", "equal without op => state(after)=" + st);
                return;
            }

            // Need a right operand
            if (st.current == null || st.current.length() == 0) {
                setError(st, "Missing operand");
                return;
            }

            BigDecimal right = parseBigDecimalSafe(st.current);
            if (right == null) {
                setError(st, "Invalid number");
                return;
            }

            if (st.acc == null) {
                // If user typed operator first then right operand, assume left=0
                st.acc = BigDecimal.ZERO;
            }

            BigDecimal res2 = eval(st.acc, right, st.op);
            if (res2 == null) {
                setError(st, "Math error");
                return;
            }

            st.acc = res2;
            st.op = null;
            st.display = format(st.acc);

            // clear current after '=' as requested
            st.current = "";
            st.justEvaluated = true;
            log("applyInput", "equal evaluated => state(after)=" + st);
            return;
        }

        log("applyInput", "unknown cb ignored: " + cb);
    }

    private void resetState(CalcState st) {
        st.display = "0";
        st.current = "";
        st.acc = null;
        st.op = null;
        st.error = false;
        st.justEvaluated = false;
    }

    private boolean isDigit(String cb) {
        return cb != null && cb.length() == 1 && cb.charAt(0) >= '0' && cb.charAt(0) <= '9';
    }

    private boolean isOperator(String cb) {
        return "+".equals(cb) || "-".equals(cb) || "x".equals(cb) || "/".equals(cb);
    }

    private void setError(CalcState st, String msg) {
        log("setError", msg + ", state(before)=" + st);
        st.error = true;
        st.display = msg;
        st.current = "";
        st.acc = null;
        st.op = null;
        st.justEvaluated = false;
        log("setError", "state(after)=" + st);
    }

    private BigDecimal parseBigDecimalSafe(String text) {
        log("parseBigDecimalSafe", "text=" + text);
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.length() == 0) {
            return null;
        }
        try {
            return new BigDecimal(t);
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal eval(BigDecimal left, BigDecimal right, String op) {
        log("eval", "left=" + left + ", right=" + right + ", op=" + op);
        try {
            if (left == null || right == null || op == null) {
                return null;
            }
            if ("+".equals(op)) {
                return left.add(right);
            }
            if ("-".equals(op)) {
                return left.subtract(right);
            }
            if ("x".equals(op)) {
                return left.multiply(right);
            }
            if ("/".equals(op)) {
                if (right.compareTo(BigDecimal.ZERO) == 0) {
                    return null;
                }
                return left.divide(right, 10, RoundingMode.HALF_UP);
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private String format(BigDecimal v) {
        log("format", "v=" + v);
        if (v == null) {
            return "0";
        }
        try {
            BigDecimal s = v.stripTrailingZeros();
            String p = s.toPlainString();
            if (p == null || p.length() == 0) {
                return "0";
            }
            return p;
        } catch (Exception e) {
            return v.toPlainString();
        }
    }

    private void log(String where, String msg) {
        System.out.println("[Calculator] " + where + ": " + msg);
    }
}
