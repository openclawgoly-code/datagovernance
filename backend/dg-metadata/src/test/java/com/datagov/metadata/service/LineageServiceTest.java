package com.datagov.metadata.service;

import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.LineageEntities.LineageEdge;
import com.datagov.metadata.entity.LineageEntities.LineageJob;
import com.datagov.metadata.entity.LineageEntities.LineageNode;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.LineageEdgeMapper;
import com.datagov.metadata.mapper.LineageJobMapper;
import com.datagov.metadata.mapper.LineageNodeMapper;
import com.datagov.metadata.service.LineageService.EdgeInput;
import com.datagov.metadata.service.LineageService.GraphView;
import com.datagov.metadata.service.LineageService.JobRequest;
import com.datagov.metadata.service.LineageService.JobResult;
import com.datagov.metadata.service.LineageService.NodeRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("字段级血缘")
class LineageServiceTest {

    private static final String WORKSPACE = "ws_alpha";
    private static final String USER = "usr_1";

    private LineageNodeMapper nodeMapper;
    private LineageJobMapper jobMapper;
    private LineageEdgeMapper edgeMapper;
    private CatalogDatasetMapper datasetMapper;
    private LineageService service;

    @BeforeEach
    void setUp() {
        nodeMapper = mock(LineageNodeMapper.class);
        jobMapper = mock(LineageJobMapper.class);
        edgeMapper = mock(LineageEdgeMapper.class);
        datasetMapper = mock(CatalogDatasetMapper.class);
        service = new LineageService(nodeMapper, jobMapper, edgeMapper, datasetMapper);
        WorkspaceContext.set(new Caller(USER, "alice", WORKSPACE, false, Set.of()));

        when(jobMapper.insert(any(LineageJob.class))).thenReturn(1);
        when(jobMapper.updateById(any(LineageJob.class))).thenReturn(1);
        when(nodeMapper.insert(any(LineageNode.class))).thenReturn(1);
        when(edgeMapper.insert(any(LineageEdge.class))).thenReturn(1);
        when(edgeMapper.updateById(any(LineageEdge.class))).thenReturn(1);
        when(edgeMapper.selectList(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Nested
    @DisplayName("写入")
    class Write {

        @Test
        @DisplayName("首次上报创建 job 与两个节点")
        void recordFirstJob() {
            JobResult result = service.recordJob(jobRequest());

            assertThat(result.jobId()).startsWith("linj_");
            assertThat(result.edges()).isEqualTo(1);
            verify(jobMapper).insert(any(LineageJob.class));
            verify(nodeMapper, times(2)).insert(any(LineageNode.class));
            verify(edgeMapper).insert(any(LineageEdge.class));
        }

        @Test
        @DisplayName("重复上报同一作业:复用节点与边,不新增边")
        void repeatedJobReusesEdge() {
            LineageJob job = new LineageJob();
            job.setId("linj_1");
            job.setWorkspaceId(WORKSPACE);
            job.setJobType("SQL");
            job.setName("订单宽表");
            when(jobMapper.selectOne(any())).thenReturn(job);

            LineageNode fromNode = node("lin_from", "COLUMN", "ods.orders.id");
            LineageNode toNode = node("lin_to", "COLUMN", "dwd.orders.id");
            when(nodeMapper.selectOne(any())).thenReturn(fromNode, toNode);

            LineageEdge edge = new LineageEdge();
            edge.setId("line_1");
            edge.setWorkspaceId(WORKSPACE);
            edge.setFromNodeId(fromNode.getId());
            edge.setToNodeId(toNode.getId());
            edge.setRelation("DERIVE");
            edge.setJobId(job.getId());
            edge.setStatus("ACTIVE");
            when(edgeMapper.selectList(any())).thenReturn(List.of(edge));

            JobResult result = service.recordJob(jobRequest());

            assertThat(result.edges()).isEqualTo(1);
            verify(edgeMapper, never()).insert(any(LineageEdge.class));
            verify(edgeMapper).updateById(edge);
        }
    }

    @Nested
    @DisplayName("遍历")
    class Graph {

        @Test
        @DisplayName("按字段 ID 解析节点:重复节点不抛 TooManyResults")
        void resolvesCatalogColumnIdWithDuplicateNodes() {
            LineageNode first = node("lin_first", "COLUMN", "dwd.orders.amount");
            first.setCatalogColumnId("col_1");
            LineageNode second = node("lin_second", "COLUMN", "dwd.orders.amount_v2");
            second.setCatalogColumnId("col_1");
            when(nodeMapper.selectList(any())).thenReturn(List.of(first, second));

            assertThat(service.getNode("col_1").id()).isEqualTo("lin_first");
            assertThat(service.getNode("lin_second").id()).isEqualTo("lin_second");
        }

        @Test
        @DisplayName("表级血缘把同一对表的列级边聚合成一条")
        void aggregatesColumnEdgesByDataset() {
            CatalogDataset sourceDataset = dataset("catds_src", "ods/sales/orders");
            CatalogDataset targetDataset = dataset("catds_dst", "dwd/sales/orders");
            LineageNode source = node("lin_src", "COLUMN", "ods/sales/orders.id");
            source.setCatalogDatasetId(sourceDataset.getId());
            source.setCatalogColumnId("col_src");
            LineageNode target = node("lin_dst", "COLUMN", "dwd/sales/orders.id");
            target.setCatalogDatasetId(targetDataset.getId());
            target.setCatalogColumnId("col_dst");
            LineageEdge edge = edge("line_ds", source.getId(), target.getId());

            when(datasetMapper.selectOne(any())).thenReturn(sourceDataset);
            when(nodeMapper.selectList(any())).thenReturn(
                    List.of(source), List.of(source, target));
            when(edgeMapper.selectList(any())).thenReturn(List.of(edge));
            when(datasetMapper.selectList(any())).thenReturn(
                    List.of(sourceDataset, targetDataset));

            GraphView graph = service.datasetGraph(sourceDataset.getId(), "downstream", 1);

            assertThat(graph.nodes()).hasSize(2);
            assertThat(graph.edges()).hasSize(1);
            assertThat(graph.edges().get(0).transformExpr()).isEqualTo("1 条列级血缘");
            assertThat(graph.edges().get(0).origin()).isEqualTo("AGGREGATED");
        }

        @Test
        @DisplayName("表级血缘跳过没有数据集归属的列级边")
        void skipsEdgesWithoutDatasetId() {
            CatalogDataset sourceDataset = dataset("catds_src", "ods/sales/orders");
            LineageNode source = node("lin_src", "COLUMN", "ods/sales/orders.id");
            source.setCatalogDatasetId(sourceDataset.getId());
            LineageNode target = node("lin_dst", "COLUMN", "unknown.column");
            LineageEdge edge = edge("line_ds", source.getId(), target.getId());

            when(datasetMapper.selectOne(any())).thenReturn(sourceDataset);
            when(nodeMapper.selectList(any())).thenReturn(
                    List.of(source), List.of(source, target));
            when(edgeMapper.selectList(any())).thenReturn(List.of(edge));

            GraphView graph = service.datasetGraph(sourceDataset.getId(), "downstream", 1);

            assertThat(graph.nodes()).isEmpty();
            assertThat(graph.edges()).isEmpty();
            assertThat(graph.unattributedEdges()).isEqualTo(1);
        }

        @Test
        @DisplayName("上游遍历返回节点与边")
        void upstream() {
            LineageNode target = node("lin_target", "COLUMN", "dwd.orders.amount");
            LineageNode source = node("lin_source", "COLUMN", "ods.orders.amount");
            when(nodeMapper.selectOne(any())).thenReturn(target);
            when(nodeMapper.selectList(any())).thenReturn(List.of(target, source));

            LineageEdge edge = new LineageEdge();
            edge.setId("line_1");
            edge.setWorkspaceId(WORKSPACE);
            edge.setFromNodeId(source.getId());
            edge.setToNodeId(target.getId());
            edge.setRelation("DERIVE");
            edge.setStatus("ACTIVE");
            when(edgeMapper.selectList(any())).thenReturn(List.of(edge));

            GraphView graph = service.upstream(target.getId(), 3);

            assertThat(graph.nodes()).hasSize(2);
            assertThat(graph.edges()).hasSize(1);
        }
    }

    private static JobRequest jobRequest() {
        return new JobRequest("订单宽表", "SQL", "job_1", "a".repeat(64),
                List.of(new EdgeInput(
                        new NodeRef("COLUMN", "ods.orders.id", "ds_ods", "col_1", "id"),
                        new NodeRef("COLUMN", "dwd.orders.id", "ds_dwd", "col_2", "id"),
                        "DERIVE", "id", 1.0, "AUTO")));
    }

    private static CatalogDataset dataset(String id, String qualifiedName) {
        CatalogDataset dataset = new CatalogDataset();
        dataset.setId(id);
        dataset.setWorkspaceId(WORKSPACE);
        dataset.setName(qualifiedName.substring(qualifiedName.lastIndexOf('/') + 1));
        dataset.setQualifiedName(qualifiedName);
        return dataset;
    }

    private static LineageEdge edge(String id, String from, String to) {
        LineageEdge edge = new LineageEdge();
        edge.setId(id);
        edge.setWorkspaceId(WORKSPACE);
        edge.setFromNodeId(from);
        edge.setToNodeId(to);
        edge.setRelation("DERIVE");
        edge.setStatus("ACTIVE");
        return edge;
    }

    private static LineageNode node(String id, String type, String qualifiedName) {
        LineageNode node = new LineageNode();
        node.setId(id);
        node.setWorkspaceId(WORKSPACE);
        node.setNodeType(type);
        node.setQualifiedName(qualifiedName);
        node.setDisplayName(qualifiedName);
        node.setCreatedAt(Instant.now());
        return node;
    }
}
