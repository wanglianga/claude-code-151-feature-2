package com.example.library;

import com.example.library.service.AppClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** REST 层冒烟：检索、预约全链路、参数校验、管理端、OpenAPI/Swagger、已还未入库申诉。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WebApiTests {

    @Autowired MockMvc mvc;
    @Autowired AppClock clock;

    @Test
    void catalogSearchAndSwaggerAreAvailable() throws Exception {
        mvc.perform(get("/api/catalog/search")
                        .param("keyword", "三体")
                        .param("pickupLibraryCode", "WEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("三体"))
                .andExpect(jsonPath("$[0].libraries[?(@.code=='EAST')].transferable")
                        .value(org.hamcrest.Matchers.hasItem(true)));

        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(org.hamcrest.Matchers.startsWith("3.")));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void holdToCheckoutFlowOverHttp_rulesAndStats() throws Exception {
        clock.setFixed(Instant.parse("2026-10-03T08:00:00Z"));

        // 预约（参数缺失 → 400）
        mvc.perform(post("/api/holds/bibs/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // R001 从城东跨馆预约到城西
        mvc.perform(post("/api/holds/bibs/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"readerCardNo":"R001","pickupLibraryCode":"WEST"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.itemBarcode").exists());

        // 物流出库 → 到馆
        var pending = mvc.perform(get("/api/transfers/tasks/pending")).andReturn();
        Number taskIdNum = com.jayway.jsonpath.JsonPath.read(pending.getResponse().getContentAsString(), "$[0].id");
        Long taskId = taskIdNum.longValue();

        mvc.perform(post("/api/transfers/pickups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskIds\":[" + taskId + "],\"staffName\":\"物流员小赵\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNo").exists());

        var afterPickup = mvc.perform(get("/api/transfers/tasks/" + taskId)).andReturn();
        String packageNo = com.jayway.jsonpath.JsonPath.read(
                afterPickup.getResponse().getContentAsString(), "$.packageNo");

        mvc.perform(post("/api/transfers/arrivals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packageNo\":\"" + packageNo + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"));

        // 读者通知与罚款账单接口
        mvc.perform(get("/api/notifications/readers/R001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("READY"));

        mvc.perform(get("/api/fines/readers/R001")).andExpect(status().isOk());

        // 管理端
        mvc.perform(get("/api/admin/dashboard").param("days", "7")).andExpect(status().isOk());
        mvc.perform(get("/api/admin/batches")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].packages").value(1));
        mvc.perform(get("/api/admin/policies")).andExpect(status().isOk())
                .andExpect(jsonPath("$['fine.daily']").value(0.5));
    }

    @Test
    void returnedNotCheckedInClaimFlowOverHttp() throws Exception {
        // R001 在中心馆借出解忧杂货店
        var checkout = mvc.perform(post("/api/loans/checkouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"readerCardNo":"R001","itemBarcode":"J-0001","libraryCode":"CENTRAL"}
                                """))
                .andExpect(status().isCreated()).andReturn();
        Number loanIdNum = com.jayway.jsonpath.JsonPath.read(
                checkout.getResponse().getContentAsString(), "$.id");
        Long loanId = loanIdNum.longValue();

        // 自助机上报一条扫描失败日志（条码未解析）
        mvc.perform(post("/api/kiosk/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"machineCode":"KIOSK-C-01","libraryCode":"CENTRAL","result":"SCAN_FAILED",
                                 "binNo":"BIN-C-2","cameraRef":"CAM-C-09","note":"扫描头故障"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("SCAN_FAILED"));

        // 读者申诉“已还未入库”，附还书机编号 → OPEN
        var claimResp = mvc.perform(post("/api/claims/loans/" + loanId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"昨晚投到中心馆自助机了","machineCode":"KIOSK-C-01"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.frozenAt").exists())
                .andReturn();
        Number claimIdNum = com.jayway.jsonpath.JsonPath.read(
                claimResp.getResponse().getContentAsString(), "$.id");
        Long claimId = claimIdNum.longValue();

        // 读者补充证据
        mvc.perform(post("/api/claims/" + claimId + "/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"RECEIPT","content":"自助机打印的还书凭条编号 RC20261003"}
                                """))
                .andExpect(status().isOk());

        // 馆员录入还书机核查 MATCH（缺 staff → 400）
        mvc.perform(post("/api/claims/" + claimId + "/checks/KIOSK")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"MATCH\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/claims/" + claimId + "/checks/KIOSK")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"staff":"馆员老周","result":"MATCH","note":"故障时段有投书失败记录"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kioskCheck").value("MATCH"));

        // 证据链可查（受理说明 + 读者凭条 + 馆员核查共 3 条）
        mvc.perform(get("/api/claims/" + claimId + "/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        // 扫描失败但书在书箱找到 → 馆方责任结案
        mvc.perform(post("/api/claims/" + claimId + "/scan-fail-found")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"staff":"馆员老周","foundLibraryCode":"CENTRAL","binNo":"BIN-C-2",
                                 "note":"书箱清点找到 J-0001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIBRARY_LIABLE"))
                .andExpect(jsonPath("$.liability").value("LIBRARY"));

        // 库存已修正为在馆可借；信用分接口可用
        mvc.perform(get("/api/credit/readers/R001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditScore").value(100));
    }
}
