/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.ppl.calcite;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.test.CalciteAssert;
import org.junit.Test;

public class CalcitePPLDedupTest extends CalcitePPLAbstractTest {

  public CalcitePPLDedupTest() {
    super(CalciteAssert.SchemaSpec.SCOTT_WITH_TEMPORAL);
  }

  @Test
  public void testDedup1() {
    String ppl = "source=EMP | dedup 1 DEPTNO";
    RelNode root = getRelNode(ppl);
    String expectedLogical =
        "LogicalProject(EMPNO=[$0], ENAME=[$1], JOB=[$2], MGR=[$3], HIREDATE=[$4], SAL=[$5],"
            + " COMM=[$6], DEPTNO=[$7])\n"
            + "  LogicalFilter(condition=[<=($8, 1)])\n"
            + "    LogicalProject(EMPNO=[$0], ENAME=[$1], JOB=[$2], MGR=[$3], HIREDATE=[$4],"
            + " SAL=[$5], COMM=[$6], DEPTNO=[$7], _row_number_dedup_=[ROW_NUMBER() OVER (PARTITION"
            + " BY $7)])\n"
            + "      LogicalFilter(condition=[IS NOT NULL($7)])\n"
            + "        LogicalTableScan(table=[[scott, EMP]])\n";
    verifyLogical(root, expectedLogical);
    String expectedResult =
        "EMPNO=7369; ENAME=SMITH; JOB=CLERK; MGR=7902; HIREDATE=1980-12-17; SAL=800.00; COMM=null;"
            + " DEPTNO=20\n"
            + "EMPNO=7782; ENAME=CLARK; JOB=MANAGER; MGR=7839; HIREDATE=1981-06-09; SAL=2450.00;"
            + " COMM=null; DEPTNO=10\n"
            + "EMPNO=7499; ENAME=ALLEN; JOB=SALESMAN; MGR=7698; HIREDATE=1981-02-20; SAL=1600.00;"
            + " COMM=300.00; DEPTNO=30\n";
    verifyResult(root, expectedResult);

    String expectedSparkSql =
        "SELECT `EMPNO`, `ENAME`, `JOB`, `MGR`, `HIREDATE`, `SAL`, `COMM`, `DEPTNO`\n"
            + "FROM (SELECT `EMPNO`, `ENAME`, `JOB`, `MGR`, `HIREDATE`, `SAL`, `COMM`, `DEPTNO`,"
            + " ROW_NUMBER() OVER (PARTITION BY `DEPTNO`) `_row_number_dedup_`\n"
            + "FROM `scott`.`EMP`\n"
            + "WHERE `DEPTNO` IS NOT NULL) `t0`\n"
            + "WHERE `_row_number_dedup_` <= 1";
    verifyPPLToSparkSQL(root, expectedSparkSql);
  }

  @Test
  public void testDedup2() {
    String ppl = "source=EMP | dedup 2 DEPTNO";
    RelNode root = getRelNode(ppl);
    String expectedLogical =
        "LogicalProject(EMPNO=[$0], ENAME=[$1], JOB=[$2], MGR=[$3], HIREDATE=[$4], SAL=[$5],"
            + " COMM=[$6], DEPTNO=[$7])\n"
            + "  LogicalFilter(condition=[<=($8, 2)])\n"
            + "    LogicalProject(EMPNO=[$0], ENAME=[$1], JOB=[$2], MGR=[$3], HIREDATE=[$4],"
            + " SAL=[$5], COMM=[$6], DEPTNO=[$7], _row_number_dedup_=[ROW_NUMBER() OVER (PARTITION"
            + " BY $7)])\n"
            + "      LogicalFilter(condition=[IS NOT NULL($7)])\n"
            + "        LogicalTableScan(table=[[scott, EMP]])\n";
    verifyLogical(root, expectedLogical);
    String expectedResult =
        "EMPNO=7369; ENAME=SMITH; JOB=CLERK; MGR=7902; HIREDATE=1980-12-17; SAL=800.00; COMM=null;"
            + " DEPTNO=20\n"
            + "EMPNO=7566; ENAME=JONES; JOB=MANAGER; MGR=7839; HIREDATE=1981-02-04; SAL=2975.00;"
            + " COMM=null; DEPTNO=20\n"
            + "EMPNO=7782; ENAME=CLARK; JOB=MANAGER; MGR=7839; HIREDATE=1981-06-09; SAL=2450.00;"
            + " COMM=null; DEPTNO=10\n"
            + "EMPNO=7839; ENAME=KING; JOB=PRESIDENT; MGR=null; HIREDATE=1981-11-17; SAL=5000.00;"
            + " COMM=null; DEPTNO=10\n"
            + "EMPNO=7499; ENAME=ALLEN; JOB=SALESMAN; MGR=7698; HIREDATE=1981-02-20; SAL=1600.00;"
            + " COMM=300.00; DEPTNO=30\n"
            + "EMPNO=7521; ENAME=WARD; JOB=SALESMAN; MGR=7698; HIREDATE=1981-02-22; SAL=1250.00;"
            + " COMM=500.00; DEPTNO=30\n";
    verifyResult(root, expectedResult);

    String expectedSparkSql =
        "SELECT `EMPNO`, `ENAME`, `JOB`, `MGR`, `HIREDATE`, `SAL`, `COMM`, `DEPTNO`\n"
            + "FROM (SELECT `EMPNO`, `ENAME`, `JOB`, `MGR`, `HIREDATE`, `SAL`, `COMM`, `DEPTNO`,"
            + " ROW_NUMBER() OVER (PARTITION BY `DEPTNO`) `_row_number_dedup_`\n"
            + "FROM `scott`.`EMP`\n"
            + "WHERE `DEPTNO` IS NOT NULL) `t0`\n"
            + "WHERE `_row_number_dedup_` <= 2";
    verifyPPLToSparkSQL(root, expectedSparkSql);
  }

  @Test
  public void testDedupKeepEmpty1() {
    String ppl = "source=EMP | dedup 1 DEPTNO, JOB keepempty=true";
    RelNode root = getRelNode(ppl);
    String expectedLogical =
        "LogicalProject(EMPNO=[$0], ENAME=[$1], JOB=[$2], MGR=[$3], HIREDATE=[$4], SAL=[$5],"
            + " COMM=[$6], DEPTNO=[$7])\n"
            + "  LogicalFilter(condition=[OR(IS NULL($7), IS NULL($2), <=($8, 1))])\n"
            + "    LogicalProject(EMPNO=[$0], ENAME=[$1], JOB=[$2], MGR=[$3], HIREDATE=[$4],"
            + " SAL=[$5], COMM=[$6], DEPTNO=[$7], _row_number_dedup_=[ROW_NUMBER() OVER (PARTITION"
            + " BY $7, $2)])\n"
            + "      LogicalTableScan(table=[[scott, EMP]])\n";
    verifyLogical(root, expectedLogical);
    String expectedResult =
        "EMPNO=7934; ENAME=MILLER; JOB=CLERK; MGR=7782; HIREDATE=1982-01-23; SAL=1300.00;"
            + " COMM=null; DEPTNO=10\n"
            + "EMPNO=7782; ENAME=CLARK; JOB=MANAGER; MGR=7839; HIREDATE=1981-06-09; SAL=2450.00;"
            + " COMM=null; DEPTNO=10\n"
            + "EMPNO=7788; ENAME=SCOTT; JOB=ANALYST; MGR=7566; HIREDATE=1987-04-19; SAL=3000.00;"
            + " COMM=null; DEPTNO=20\n"
            + "EMPNO=7499; ENAME=ALLEN; JOB=SALESMAN; MGR=7698; HIREDATE=1981-02-20; SAL=1600.00;"
            + " COMM=300.00; DEPTNO=30\n"
            + "EMPNO=7566; ENAME=JONES; JOB=MANAGER; MGR=7839; HIREDATE=1981-02-04; SAL=2975.00;"
            + " COMM=null; DEPTNO=20\n"
            + "EMPNO=7369; ENAME=SMITH; JOB=CLERK; MGR=7902; HIREDATE=1980-12-17; SAL=800.00;"
            + " COMM=null; DEPTNO=20\n"
            + "EMPNO=7900; ENAME=JAMES; JOB=CLERK; MGR=7698; HIREDATE=1981-12-03; SAL=950.00;"
            + " COMM=null; DEPTNO=30\n"
            + "EMPNO=7698; ENAME=BLAKE; JOB=MANAGER; MGR=7839; HIREDATE=1981-01-05; SAL=2850.00;"
            + " COMM=null; DEPTNO=30\n"
            + "EMPNO=7839; ENAME=KING; JOB=PRESIDENT; MGR=null; HIREDATE=1981-11-17; SAL=5000.00;"
            + " COMM=null; DEPTNO=10\n";
    verifyResult(root, expectedResult);

    String expectedSparkSql =
        "SELECT `EMPNO`, `ENAME`, `JOB`, `MGR`, `HIREDATE`, `SAL`, `COMM`, `DEPTNO`\n"
            + "FROM (SELECT `EMPNO`, `ENAME`, `JOB`, `MGR`, `HIREDATE`, `SAL`, `COMM`, `DEPTNO`,"
            + " ROW_NUMBER() OVER (PARTITION BY `DEPTNO`, `JOB`) `_row_number_dedup_`\n"
            + "FROM `scott`.`EMP`) `t`\n"
            + "WHERE `DEPTNO` IS NULL OR `JOB` IS NULL OR `_row_number_dedup_` <= 1";
    verifyPPLToSparkSQL(root, expectedSparkSql);
  }

  @Test
  public void testDedupKeepEmpty2() {
    String ppl = "source=EMP | dedup 2 DEPTNO, JOB keepempty=true";
    RelNode root = getRelNode(ppl);
    String expectedLogical =
        "LogicalProject(EMPNO=[$0], ENAME=[$1], JOB=[$2], MGR=[$3], HIREDATE=[$4], SAL=[$5],"
            + " COMM=[$6], DEPTNO=[$7])\n"
            + "  LogicalFilter(condition=[OR(IS NULL($7), IS NULL($2), <=($8, 2))])\n"
            + "    LogicalProject(EMPNO=[$0], ENAME=[$1], JOB=[$2], MGR=[$3], HIREDATE=[$4],"
            + " SAL=[$5], COMM=[$6], DEPTNO=[$7], _row_number_dedup_=[ROW_NUMBER() OVER (PARTITION"
            + " BY $7, $2)])\n"
            + "      LogicalTableScan(table=[[scott, EMP]])\n";
    verifyLogical(root, expectedLogical);
    String expectedResult =
        "EMPNO=7934; ENAME=MILLER; JOB=CLERK; MGR=7782; HIREDATE=1982-01-23; SAL=1300.00;"
            + " COMM=null; DEPTNO=10\n"
            + "EMPNO=7782; ENAME=CLARK; JOB=MANAGER; MGR=7839; HIREDATE=1981-06-09; SAL=2450.00;"
            + " COMM=null; DEPTNO=10\n"
            + "EMPNO=7788; ENAME=SCOTT; JOB=ANALYST; MGR=7566; HIREDATE=1987-04-19; SAL=3000.00;"
            + " COMM=null; DEPTNO=20\n"
            + "EMPNO=7902; ENAME=FORD; JOB=ANALYST; MGR=7566; HIREDATE=1981-12-03; SAL=3000.00;"
            + " COMM=null; DEPTNO=20\n"
            + "EMPNO=7499; ENAME=ALLEN; JOB=SALESMAN; MGR=7698; HIREDATE=1981-02-20; SAL=1600.00;"
            + " COMM=300.00; DEPTNO=30\n"
            + "EMPNO=7521; ENAME=WARD; JOB=SALESMAN; MGR=7698; HIREDATE=1981-02-22; SAL=1250.00;"
            + " COMM=500.00; DEPTNO=30\n"
            + "EMPNO=7566; ENAME=JONES; JOB=MANAGER; MGR=7839; HIREDATE=1981-02-04; SAL=2975.00;"
            + " COMM=null; DEPTNO=20\n"
            + "EMPNO=7369; ENAME=SMITH; JOB=CLERK; MGR=7902; HIREDATE=1980-12-17; SAL=800.00;"
            + " COMM=null; DEPTNO=20\n"
            + "EMPNO=7876; ENAME=ADAMS; JOB=CLERK; MGR=7788; HIREDATE=1987-05-23; SAL=1100.00;"
            + " COMM=null; DEPTNO=20\n"
            + "EMPNO=7900; ENAME=JAMES; JOB=CLERK; MGR=7698; HIREDATE=1981-12-03; SAL=950.00;"
            + " COMM=null; DEPTNO=30\n"
            + "EMPNO=7698; ENAME=BLAKE; JOB=MANAGER; MGR=7839; HIREDATE=1981-01-05; SAL=2850.00;"
            + " COMM=null; DEPTNO=30\n"
            + "EMPNO=7839; ENAME=KING; JOB=PRESIDENT; MGR=null; HIREDATE=1981-11-17; SAL=5000.00;"
            + " COMM=null; DEPTNO=10\n";
    verifyResult(root, expectedResult);

    String expectedSparkSql =
        "SELECT `EMPNO`, `ENAME`, `JOB`, `MGR`, `HIREDATE`, `SAL`, `COMM`, `DEPTNO`\n"
            + "FROM (SELECT `EMPNO`, `ENAME`, `JOB`, `MGR`, `HIREDATE`, `SAL`, `COMM`, `DEPTNO`,"
            + " ROW_NUMBER() OVER (PARTITION BY `DEPTNO`, `JOB`) `_row_number_dedup_`\n"
            + "FROM `scott`.`EMP`) `t`\n"
            + "WHERE `DEPTNO` IS NULL OR `JOB` IS NULL OR `_row_number_dedup_` <= 2";
    verifyPPLToSparkSQL(root, expectedSparkSql);
  }

  @Test
  public void testDedupExpr() {
    String ppl =
        "source=EMP | eval NEW_DEPTNO = DEPTNO + 1 | fields EMPNO, ENAME, JOB, DEPTNO, NEW_DEPTNO |"
            + " dedup 1 NEW_DEPTNO";
    RelNode root = getRelNode(ppl);
    String expectedLogical =
        "LogicalProject(EMPNO=[$0], ENAME=[$1], JOB=[$2], DEPTNO=[$3], NEW_DEPTNO=[$4])\n"
            + "  LogicalFilter(condition=[<=($5, 1)])\n"
            + "    LogicalProject(EMPNO=[$0], ENAME=[$1], JOB=[$2], DEPTNO=[$3], NEW_DEPTNO=[$4],"
            + " _row_number_dedup_=[ROW_NUMBER() OVER (PARTITION BY $4)])\n"
            + "      LogicalFilter(condition=[IS NOT NULL($4)])\n"
            + "        LogicalProject(EMPNO=[$0], ENAME=[$1], JOB=[$2], DEPTNO=[$7],"
            + " NEW_DEPTNO=[+($7, 1)])\n"
            + "          LogicalTableScan(table=[[scott, EMP]])\n";
    verifyLogical(root, expectedLogical);
    ppl =
        "source=EMP | fields EMPNO, ENAME, JOB, DEPTNO | eval NEW_DEPTNO = DEPTNO + 1 | dedup 1"
            + " NEW_DEPTNO";
    root = getRelNode(ppl);
    verifyLogical(root, expectedLogical);
    ppl =
        "source=EMP | eval NEW_DEPTNO = DEPTNO + 1 | fields NEW_DEPTNO, EMPNO, ENAME, JOB | dedup 1"
            + " JOB";
    root = getRelNode(ppl);
    expectedLogical =
        "LogicalProject(NEW_DEPTNO=[$0], EMPNO=[$1], ENAME=[$2], JOB=[$3])\n"
            + "  LogicalFilter(condition=[<=($4, 1)])\n"
            + "    LogicalProject(NEW_DEPTNO=[$0], EMPNO=[$1], ENAME=[$2], JOB=[$3],"
            + " _row_number_dedup_=[ROW_NUMBER() OVER (PARTITION BY $3)])\n"
            + "      LogicalFilter(condition=[IS NOT NULL($3)])\n"
            + "        LogicalProject(NEW_DEPTNO=[+($7, 1)], EMPNO=[$0], ENAME=[$1], JOB=[$2])\n"
            + "          LogicalTableScan(table=[[scott, EMP]])\n";
    verifyLogical(root, expectedLogical);
    ppl =
        "source=EMP | eval NEW_DEPTNO = DEPTNO + 1 | fields NEW_DEPTNO, EMPNO, ENAME, JOB | sort"
            + " NEW_DEPTNO | dedup 1 NEW_DEPTNO";
    root = getRelNode(ppl);
    // Sort is stripped from below the window and moved to the top to ensure order is preserved
    expectedLogical =
        "LogicalSort(sort0=[$0], dir0=[ASC-nulls-first])\n"
            + "  LogicalProject(NEW_DEPTNO=[$0], EMPNO=[$1], ENAME=[$2], JOB=[$3])\n"
            + "    LogicalFilter(condition=[<=($4, 1)])\n"
            + "      LogicalProject(NEW_DEPTNO=[$0], EMPNO=[$1], ENAME=[$2], JOB=[$3],"
            + " _row_number_dedup_=[ROW_NUMBER() OVER (PARTITION BY $0 ORDER BY $0 NULLS"
            + " FIRST)])\n"
            + "        LogicalFilter(condition=[IS NOT NULL($0)])\n"
            + "          LogicalProject(NEW_DEPTNO=[+($7, 1)], EMPNO=[$0], ENAME=[$1], JOB=[$2])\n"
            + "            LogicalTableScan(table=[[scott, EMP]])\n";
    verifyLogical(root, expectedLogical);
  }

  /** Regression test for https://github.com/opensearch-project/sql/issues/3922 */
  @Test
  public void testSortThenDedup() {
    String ppl = "source=EMP | sort DEPTNO | dedup 1 JOB | fields DEPTNO, ENAME, JOB";
    RelNode root = getRelNode(ppl);
    String expectedLogical =
        "LogicalProject(DEPTNO=[$7], ENAME=[$1], JOB=[$2])\n"
            + "  LogicalSort(sort0=[$7], dir0=[ASC-nulls-first])\n"
            + "    LogicalProject(EMPNO=[$0], ENAME=[$1], JOB=[$2], MGR=[$3], HIREDATE=[$4],"
            + " SAL=[$5], COMM=[$6], DEPTNO=[$7])\n"
            + "      LogicalFilter(condition=[<=($8, 1)])\n"
            + "        LogicalProject(EMPNO=[$0], ENAME=[$1], JOB=[$2], MGR=[$3], HIREDATE=[$4],"
            + " SAL=[$5], COMM=[$6], DEPTNO=[$7], _row_number_dedup_=[ROW_NUMBER() OVER (PARTITION"
            + " BY $2 ORDER BY $7 NULLS FIRST)])\n"
            + "          LogicalFilter(condition=[IS NOT NULL($2)])\n"
            + "            LogicalTableScan(table=[[scott, EMP]])\n";
    verifyLogical(root, expectedLogical);
    // After fix, the sort order (DEPTNO ASC) must be preserved through dedup.
    // The correct result has DEPTNO in ascending order: 10, 10, 10, 20, 30.
    String expectedResult =
        "DEPTNO=10; ENAME=MILLER; JOB=CLERK\n"
            + "DEPTNO=10; ENAME=KING; JOB=PRESIDENT\n"
            + "DEPTNO=10; ENAME=CLARK; JOB=MANAGER\n"
            + "DEPTNO=20; ENAME=SCOTT; JOB=ANALYST\n"
            + "DEPTNO=30; ENAME=ALLEN; JOB=SALESMAN\n";
    verifyResult(root, expectedResult);
  }

  /** Regression test for https://github.com/opensearch-project/sql/issues/3922 */
  @Test
  public void testSortThenDedupWithEval() {
    String ppl =
        "source=EMP | eval NEW_DEPTNO = DEPTNO + 1 | fields NEW_DEPTNO, EMPNO, ENAME, JOB | sort"
            + " NEW_DEPTNO | dedup 1 NEW_DEPTNO";
    RelNode root = getRelNode(ppl);
    String expectedLogical =
        "LogicalSort(sort0=[$0], dir0=[ASC-nulls-first])\n"
            + "  LogicalProject(NEW_DEPTNO=[$0], EMPNO=[$1], ENAME=[$2], JOB=[$3])\n"
            + "    LogicalFilter(condition=[<=($4, 1)])\n"
            + "      LogicalProject(NEW_DEPTNO=[$0], EMPNO=[$1], ENAME=[$2], JOB=[$3],"
            + " _row_number_dedup_=[ROW_NUMBER() OVER (PARTITION BY $0 ORDER BY $0 NULLS"
            + " FIRST)])\n"
            + "        LogicalFilter(condition=[IS NOT NULL($0)])\n"
            + "          LogicalProject(NEW_DEPTNO=[+($7, 1)], EMPNO=[$0], ENAME=[$1], JOB=[$2])\n"
            + "            LogicalTableScan(table=[[scott, EMP]])\n";
    verifyLogical(root, expectedLogical);
    // After fix, the sort order (NEW_DEPTNO ASC) must be preserved through dedup.
    // The correct result has NEW_DEPTNO in ascending order: 11, 21, 31.
    String expectedResult =
        "NEW_DEPTNO=11; EMPNO=7782; ENAME=CLARK; JOB=MANAGER\n"
            + "NEW_DEPTNO=21; EMPNO=7369; ENAME=SMITH; JOB=CLERK\n"
            + "NEW_DEPTNO=31; EMPNO=7499; ENAME=ALLEN; JOB=SALESMAN\n";
    verifyResult(root, expectedResult);
  }

  @Test
  public void testRenameDedup() {
    String ppl =
        "source=EMP | eval TEMP_DEPTNO = DEPTNO + 1 | rename TEMP_DEPTNO as NEW_DEPTNO | fields"
            + " NEW_DEPTNO, EMPNO, ENAME, JOB | dedup 1 NEW_DEPTNO";
    RelNode root = getRelNode(ppl);
    String expectedLogical =
        "LogicalProject(NEW_DEPTNO=[$0], EMPNO=[$1], ENAME=[$2], JOB=[$3])\n"
            + "  LogicalFilter(condition=[<=($4, 1)])\n"
            + "    LogicalProject(NEW_DEPTNO=[$0], EMPNO=[$1], ENAME=[$2], JOB=[$3],"
            + " _row_number_dedup_=[ROW_NUMBER() OVER (PARTITION BY $0)])\n"
            + "      LogicalFilter(condition=[IS NOT NULL($0)])\n"
            + "        LogicalProject(NEW_DEPTNO=[+($7, 1)], EMPNO=[$0], ENAME=[$1], JOB=[$2])\n"
            + "          LogicalTableScan(table=[[scott, EMP]])\n";
    verifyLogical(root, expectedLogical);
    ppl =
        "source=EMP | eval TEMP_DEPTNO = DEPTNO + 1 | rename TEMP_DEPTNO as NEW_DEPTNO | fields"
            + " NEW_DEPTNO, EMPNO, ENAME, JOB | dedup 1 JOB";
    root = getRelNode(ppl);
    expectedLogical =
        "LogicalProject(NEW_DEPTNO=[$0], EMPNO=[$1], ENAME=[$2], JOB=[$3])\n"
            + "  LogicalFilter(condition=[<=($4, 1)])\n"
            + "    LogicalProject(NEW_DEPTNO=[$0], EMPNO=[$1], ENAME=[$2], JOB=[$3],"
            + " _row_number_dedup_=[ROW_NUMBER() OVER (PARTITION BY $3)])\n"
            + "      LogicalFilter(condition=[IS NOT NULL($3)])\n"
            + "        LogicalProject(NEW_DEPTNO=[+($7, 1)], EMPNO=[$0], ENAME=[$1], JOB=[$2])\n"
            + "          LogicalTableScan(table=[[scott, EMP]])\n";
    verifyLogical(root, expectedLogical);
    ppl =
        "source=EMP | eval TEMP_DEPTNO = DEPTNO + 1 | rename TEMP_DEPTNO as NEW_DEPTNO | fields"
            + " NEW_DEPTNO, EMPNO, ENAME, JOB | sort NEW_DEPTNO | dedup 1 NEW_DEPTNO";
    root = getRelNode(ppl);
    // Sort is stripped from below the window and moved to the top to ensure order is preserved
    expectedLogical =
        "LogicalSort(sort0=[$0], dir0=[ASC-nulls-first])\n"
            + "  LogicalProject(NEW_DEPTNO=[$0], EMPNO=[$1], ENAME=[$2], JOB=[$3])\n"
            + "    LogicalFilter(condition=[<=($4, 1)])\n"
            + "      LogicalProject(NEW_DEPTNO=[$0], EMPNO=[$1], ENAME=[$2], JOB=[$3],"
            + " _row_number_dedup_=[ROW_NUMBER() OVER (PARTITION BY $0 ORDER BY $0 NULLS"
            + " FIRST)])\n"
            + "        LogicalFilter(condition=[IS NOT NULL($0)])\n"
            + "          LogicalProject(NEW_DEPTNO=[+($7, 1)], EMPNO=[$0], ENAME=[$1], JOB=[$2])\n"
            + "            LogicalTableScan(table=[[scott, EMP]])\n";
    verifyLogical(root, expectedLogical);
  }

  /**
   * Edge case: sort field is projected away before dedup. The sort collation references a field
   * (DEPTNO) that is no longer in the schema after the fields command. The dedup should still work
   * correctly but without the sort-restore optimization since the sort field is unavailable.
   */
  @Test
  public void testSortFieldProjectedAwayBeforeDedup() {
    String ppl = "source=EMP | sort DEPTNO | fields ENAME, JOB | dedup 1 JOB";
    RelNode root = getRelNode(ppl);
    // No restore Sort at top because DEPTNO was projected away
    String expectedLogical =
        "LogicalProject(ENAME=[$0], JOB=[$1])\n"
            + "  LogicalFilter(condition=[<=($2, 1)])\n"
            + "    LogicalProject(ENAME=[$0], JOB=[$1], _row_number_dedup_=[ROW_NUMBER() OVER"
            + " (PARTITION BY $1)])\n"
            + "      LogicalFilter(condition=[IS NOT NULL($1)])\n"
            + "        LogicalProject(ENAME=[$1], JOB=[$2])\n"
            + "          LogicalSort(sort0=[$7], dir0=[ASC-nulls-first])\n"
            + "            LogicalTableScan(table=[[scott, EMP]])\n";
    verifyLogical(root, expectedLogical);
  }

  /**
   * Regression test for issue #7: when a user {@code where} sits below {@code dedup}, the HEP
   * program in {@code CalciteToolsHelper} must still produce a {@link
   * org.opensearch.sql.calcite.plan.rel.LogicalDedup}. Before the fix, both rules were registered
   * via {@code addRuleCollection}, so {@code FilterMergeRule} could fire ahead of {@code
   * PPLSimplifyDedupRule} and merge the user predicate into the bucket-non-null filter; the
   * simplify rule's bottom operand then rejected the merged condition (it only accepts pure {@code
   * IS NOT NULL}/AND-of-{@code IS NOT NULL}), no {@code LogicalDedup} was produced, and dedup
   * pushdown to the OpenSearch storage engine was silently disabled. The fix is to register the two
   * rules with separate {@code addRuleInstance} calls in the order simplify-dedup first (to
   * fixpoint), then filter-merge.
   */
  @Test
  public void testWhereThenDedupProducesLogicalDedup() {
    // Use a where predicate on a DIFFERENT column from the dedup column. With the same column,
    // Calcite's RexSimplify can fold AND(IS_NOT_NULL(x), >(x, c)) down to >(x, c), masking the
    // bug. The issue's reproducer (where on @timestamp, dedup on namespace) hits this exact
    // shape.
    String ppl = "source=EMP | where SAL > 1000 | dedup 1 DEPTNO | fields DEPTNO";
    RelNode optimized = getRelNodeAfterCalciteHep(ppl);
    String optimizedPlan = optimized.explain();
    org.junit.Assert.assertTrue(
        "where + dedup must produce a LogicalDedup so OpenSearch DedupPushdownRule can match;"
            + " actual plan was:\n"
            + optimizedPlan,
        optimizedPlan.contains("LogicalDedup"));
    // The window-form leftover would indicate the simplify rule did not fire — assert it is gone.
    org.junit.Assert.assertFalse(
        "ROW_NUMBER window must be consumed by PPLSimplifyDedupRule when where + dedup are"
            + " combined; actual plan was:\n"
            + optimizedPlan,
        optimizedPlan.contains("ROW_NUMBER"));
  }

  /**
   * Adversarial regression test: simulates the pathological order described in issue #7 by forcing
   * FilterMergeRule to run to fixpoint BEFORE PPLSimplifyDedupRule. This documents the failure mode
   * the fix in {@code CalciteToolsHelper} prevents — once the bucket-non-null filter has been
   * merged with the user {@code WHERE}, {@code mayBeFilterFromBucketNonNull} can never accept the
   * combined condition, so {@code PPLSimplifyDedupRule} is permanently unable to produce a {@code
   * LogicalDedup}. The production fix enforces order at the program level (sequential {@code
   * addRuleInstance} calls), making this hazard unreachable.
   */
  @Test
  public void testFilterMergeBeforeSimplifyDedupBreaksPattern() {
    String ppl = "source=EMP | where SAL > 1000 | dedup 1 DEPTNO | fields DEPTNO";
    // getRelNode already runs FilterMergeRule on the raw plan, simulating the pathological
    // schedule where FilterMergeRule fires before PPLSimplifyDedupRule.
    RelNode mergedFirst = getRelNode(ppl);
    org.apache.calcite.plan.hep.HepProgram simplifyOnly =
        new org.apache.calcite.plan.hep.HepProgramBuilder()
            .addRuleInstance(
                org.opensearch.sql.calcite.plan.rule.PPLSimplifyDedupRule.DEDUP_SIMPLIFY_RULE)
            .build();
    org.apache.calcite.plan.hep.HepPlanner planner =
        new org.apache.calcite.plan.hep.HepPlanner(simplifyOnly);
    planner.setRoot(mergedFirst);
    RelNode result = planner.findBestExp();
    String plan = result.explain();
    org.junit.Assert.assertFalse(
        "If FilterMergeRule runs before PPLSimplifyDedupRule, the simplify rule must NOT recover"
            + " — the merged AND(IS_NOT_NULL, user_predicate) filter fails the bucket-non-null"
            + " predicate. This documents why the production HEP program enforces ordering via"
            + " separate addRuleInstance calls (PPLSimplifyDedupRule first, then FilterMergeRule)."
            + " Actual plan was:\n"
            + plan,
        plan.contains("LogicalDedup"));
    org.junit.Assert.assertTrue(
        "Plan should still contain ROW_NUMBER window form when simplify fails. Actual plan was:\n"
            + plan,
        plan.contains("ROW_NUMBER"));
  }
}
