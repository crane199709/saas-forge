package io.saasforge.sdk.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PageQueryTest {
    @Autowired
    MockMvc mvc;

    @Test
    void defaultsToFirstPageOfTwenty() throws Exception {
        mvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ok"))
                .andExpect(jsonPath("$.data[0].id").value("o-1"))
                .andExpect(jsonPath("$.meta.page.number").value(1))
                .andExpect(jsonPath("$.meta.page.size").value(20))
                .andExpect(jsonPath("$.meta.page.totalElements").value(135))
                .andExpect(jsonPath("$.meta.page.totalPages").value(7));
    }

    @Test
    void acceptsMaxSizeAndWhitelistedSorts() throws Exception {
        mvc.perform(get("/orders?page=2&size=100&sort=createdAt,desc&sort=amount,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page.number").value(2))
                .andExpect(jsonPath("$.meta.page.size").value(100))
                .andExpect(jsonPath("$.meta.page.totalPages").value(2));
    }

    @Test
    void sizeAboveLimitIsRejectedNotTruncated() throws Exception {
        mvc.perform(get("/orders?size=101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("request.invalid"))
                .andExpect(jsonPath("$.errors[0].field").value("size"))
                .andExpect(jsonPath("$.errors[0].code").value("validation.size"))
                .andExpect(jsonPath("$.errors[0].userTip").value("每页条数必须是 1 到 100 之间的整数"));
    }

    @Test
    void pageStartsAtOne() throws Exception {
        mvc.perform(get("/orders?page=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("request.invalid"))
                .andExpect(jsonPath("$.errors[0].field").value("page"));
    }

    @Test
    void nonNumericPageIsInvalid() throws Exception {
        mvc.perform(get("/orders?page=abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("request.invalid"));
    }

    @Test
    void sortOutsideWhitelistIsRejected() throws Exception {
        mvc.perform(get("/orders?sort=password,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("request.invalid"))
                .andExpect(jsonPath("$.errors[0].field").value("sort"))
                .andExpect(jsonPath("$.errors[0].userTip").value("排序字段或方向不受支持"));
    }

    @Test
    void unknownSortDirectionIsRejected() throws Exception {
        mvc.perform(get("/orders?sort=amount,sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("sort"));
    }

    @Test
    void allViolationsAreReportedTogether() throws Exception {
        mvc.perform(get("/orders?page=0&size=0&sort=x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(3));
    }
}
