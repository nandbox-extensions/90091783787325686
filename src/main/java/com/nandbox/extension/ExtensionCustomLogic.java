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
        String display;     // what to show
        String current;     // current number being entered (as string)
        BigDecimal acc;     // accumulator / left operand
        String op;          // pending operator: + - x /
        boolean error;
        boolean justEvaluated; // true immediately after '='

        CalcState() {
            this.display = "0";
            this.current = "";
            this.acc = null;
            this.op = null;
            this.error = false;
            this.justEvaluated = false;
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

        // Only handle our calculator menu
        if (menuId == null || !"dXNU7K3mDKkhECn".equals(menuId)) {
            return;
        }

        // Determine which callback fired: for button menus, the event comes with a single cell
        String callback = null;
        for (int i = 0; i < cells.size(); i++) {
            MenuCallback.Cell cell = (MenuCallback.Cell) cells.get(i);
            if (cell != null && cell.getCallback() != null && cell.getCallback().length() > 0) {
                callback = cell.getCallback();
                break;
            }
        }
        if (callback == null) {
            return;
        }

        CalcState st = (CalcState) stateByUser.get(userId);
        if (st == null) {
            st = new CalcState();
            stateByUser.put(userId, st);
        }

        applyInput(st, callback);

        // Update output separator cell by callback "output"
        JSONArray updatedCells = new JSONArray();
        JSONObject outCell = new JSONObject();
        outCell.put("callback", "output");
        outCell.put("form", "output");
        outCell.put("headline", st.display);
        outCell.put("subhead", "");
        updatedCells.add(outCell);

        api.updateMenuCell(userId, menuId, appId, updatedCells, Utils.getUniqueId(), Boolean.FALSE);
    }

    private void applyInput(CalcState st, String cb) {
        if (st.error) {
            // reset after error on next press
            st.error = false;
            st.acc = null;
            st.op = null;
            st.current = "";
            st.display = "0";
            st.justEvaluated = false;
        }

        if (isDigit(cb)) {
            // if we just evaluated and user starts typing, start a new entry
            if (st.justEvaluated) {
                st.acc = null;
                st.op = null;
                st.current = "";
                st.display = "0";
                st.justEvaluated = false;
            }

            if (st.current == null || st.current.length() == 0 || "0".equals(st.current)) {
                st.current = cb;
            } else {
                st.current = st.current + cb;
            }
            st.display = st.current;
            return;
        }

        if (".".equals(cb)) {
            if (st.justEvaluated) {
                st.acc = null;
                st.op = null;
                st.current = "";
                st.display = "0";
                st.justEvaluated = false;
            }

            if (st.current == null || st.current.length() == 0) {
                st.current = "0.";
            } else if (st.current.indexOf('.') < 0) {
                st.current = st.current + ".";
            }
            st.display = st.current;
            return;
        }

        if (isOperator(cb)) {
            st.justEvaluated = false;

            // If we have a current number, fold it into accumulator first
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
                // No current number: if no accumulator, keep it empty (operator first => no-op)
                if (st.acc == null) {
                    st.op = cb; // allow selecting an operator first; still no calculation
                    st.display = "0";
                    return;
                }
            }

            st.op = cb;
            if (st.acc != null) {
                st.display = format(st.acc);
            }
            return;
        }

        if ("=".equals(cb)) {
            // Require a complete operation: acc, operator, and right operand
            if (st.op == null || st.acc == null) {
                // '=' without pending operation: just keep current display (no fake "1")
                if (st.current != null && st.current.length() > 0) {
                    st.display = st.current;
                } else if (st.acc != null) {
                    st.display = format(st.acc);
                } else {
                    st.display = "0";
                }
                st.current = ""; // clear after '=' as requested
                st.justEvaluated = true;
                return;
            }

            if (st.current == null || st.current.length() == 0) {
                setError(st, "Missing operand");
                return;
            }

            BigDecimal right = parseBigDecimalSafe(st.current);
            if (right == null) {
                setError(st, "Invalid number");
                return;
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
            return;
        }

        // Unknown callback: ignore
    }

    private boolean isDigit(String cb) {
        return cb != null && cb.length() == 1 && cb.charAt(0) >= '0' && cb.charAt(0) <= '9';
    }

    private boolean isOperator(String cb) {
        return "+".equals(cb) || "-".equals(cb) || "x".equals(cb) || "/".equals(cb);
    }

    private void setError(CalcState st, String msg) {
        st.error = true;
        st.display = msg;
        st.current = "";
        st.acc = null;
        st.op = null;
        st.justEvaluated = false;
    }

    private BigDecimal parseBigDecimalSafe(String text) {
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
        try {
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
}
