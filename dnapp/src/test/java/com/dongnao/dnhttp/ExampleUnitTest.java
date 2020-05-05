package com.dongnao.dnhttp;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {
    @Test
    public void addition_isCorrect() throws Exception {
        assertEquals(4, 2 + 2);
//       0
String l = "{\"message\":\"快递公司参数异常：单号不存在或者已经过期\",\"nu\":\"\",\"ischeck\":\"0\"," +
        "\"condition\":\"\"," +
        "\"com\":\"\",\"status\":\"201\",\"state\":\"0\",\"data\":[]}";
        System.out.println(l.getBytes().length);
        System.out.println(Integer.valueOf("9d",16));

    }
}