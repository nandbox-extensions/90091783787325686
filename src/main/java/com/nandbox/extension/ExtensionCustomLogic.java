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
        String display;         // what to show in output cell
        String current;         // current number being entered (string form)
        BigDecimal acc;         // left operand / accumulator
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
        log("onConnect", "connected");
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

        log("onMenuCallBack", "userId=" + userId + ", callback=" + callback + ", before=" + st);

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
        log("findFirstCallback", "cells.size=" + cells.size());
        for (int i = 0; i < cells.size(); i++) {
            MenuCallback.Cell cell = (MenuCallback.Cell) cells.get(i);
            if (cell != null && cell.getCallback() != null && cell.getCallback().length() > 0) {
                log("findFirstCallback", "found callback=" + cell.getCallback());
                return cell.getCallback();
            }
        }
        return null;
    }

    private void applyInput(CalcState st, String cb) {
        log("applyInput", "cb=" + cb + ", state(before)=" + st);

        // Reset button support (common callbacks)
        if ("C".equals(cb) || "reset".equals(cb) || "Reset".equals(cb) || "clear".equals(cb)) {
            resetState(st);
            log("applyInput", "reset pressed, state(after)=" + st);
            return;
        }

        if (st.error) {
            resetState(st);
            log("applyInput", "state cleared after error");
        }

        // Digits
        if (isDigit(cb)) {
            if (st.justEvaluated) {
                // Start new expression after '=' when user types a number
                resetState(st);
                st.justEvaluated = false;
                log("applyInput", "new entry after '='");
            }

            if (st.current == null || st.current.length() == 0) {
                st.current = cb;
            } else if ("0".equals(st.current)) {
                st.current = cb;
            } else {
                st.current = st.current + cb;
            }

            // Show whole equation (if any)
            st.display = buildEquationDisplay(st);
            log("applyInput", "digit => state(after)=" + st);
            return;
        }

        // Dot
        if (".".equals(cb)) {
            if (st.justEvaluated) {
                resetState(st);
                st.justEvaluated = false;
                log("applyInput", "new entry after '=' with dot");
            }

            if (st.current == null || st.current.length() == 0) {
                st.current = "0.";
            } else if (st.current.indexOf('.') < 0) {
                st.current = st.current + ".";
            }

            st.display = buildEquationDisplay(st);
            log("applyInput", "dot => state(after)=" + st);
            return;
        }

        // Operators
        if (isOperator(cb)) {
            st.justEvaluated = false;

            // If user is entering a number, commit it to acc or compute intermediate result
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
                // No current: if acc is null, assume 0 as left operand
                if (st.acc == null) {
                    st.acc = BigDecimal.ZERO;
                }
            }

            // Set/replace operator
            st.op = cb;

            // Display equation so far: "<acc> <op>"
            st.display = buildEquationDisplay(st);
            log("applyInput", "operator => state(after)=" + st);
            return;
        }

        // Equals: calculate only here
        if ("=".equals(cb)) {
            // If nothing entered, keep display at 0
            if (st.acc == null && (st.current == null || st.current.length() == 0)) {
                st.display = "0";
                st.current = "";
                st.op = null;
                st.justEvaluated = true;
                log("applyInput", "equal with nothing => state(after)=" + st);
                return;
            }

            // If no operator: just finalize what we have (no invented '1')
            if (st.op == null) {
                if (st.current != null && st.current.length() > 0) {
                    st.display = st.current;
                } else if (st.acc != null) {
                    st.display = format(st.acc);
                } else {
                    st.display = "0";
                }
                st.current = ""; // clear current after '='
                st.justEvaluated = true;
                log("applyInput", "equal without op => state(after)=" + st);
                return;
            }

            // Need right operand
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
                st.acc = BigDecimal.ZERO;
            }

            // Show full equation once before result
            String eq = format(st.acc) + " " + st.op + " " + st.current;
            log("applyInput", "equal pressed, equation=" + eq);

            BigDecimal res2 = eval(st.acc, right, st.op);
            if (res2 == null) {
                setError(st, "Math error");
                return;
            }

            st.acc = res2;
            st.op = null;
            st.display = eq + " = " + format(st.acc);

            // clear current after '=' as requested
            st.current = "";
            st.justEvaluated = true;
            log("applyInput", "equal evaluated => state(after)=" + st);
            return;
        }

        log("applyInput", "unknown cb ignored: " + cb);
    }

    private String buildEquationDisplay(CalcState st) {
        // Displays the equation being built, but does not compute here.
        // Cases:
        // - only current: "123"
        // - acc + op: "5 +"
        // - acc + op + current: "5 + 2"
        if (st == null) {
            return "0";
        }

        String accStr = (st.acc == null) ? null : format(st.acc);
        String curStr = (st.current == null || st.current.length() == 0) ? null : st.current;
        String opStr = (st.op == null || st.op.length() == 0) ? null : st.op;

        if (accStr == null && curStr == null) {
            return "0";
        }
        if (accStr == null) {
            return curStr;
        }
        if (opStr == null) {
            return accStr;
        }
        if (curStr == null) {
            return accStr + " " + opStr;
        }
        return accStr + " " + opStr + " " + curStr;
    }

    private void resetState(CalcState st) {
        log("resetState", "before=" + st);
        st.display = "0";
        st.current = "";
        st.acc = null;
        st.op = null;
        st.error = false;
        st.justEvaluated = false;
        log("resetState", "after=" + st);
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
