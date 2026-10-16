// 搜索统一路径（bookapi tab 11）与官网分类翻页验证：需在可达番茄 API 的环境运行（GitHub Actions）。
// 用法：node scripts/verify-search.mjs —— 供 .github/workflows/verify-search.yml 调用。
import { createRequire } from 'node:module';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const requireCjs = createRequire(import.meta.url);
const shimDir = mkdtempSync(join(tmpdir(), 'hongguo-verify-'));
let source = readFileSync(new URL('../server/vendor/hongguo.cjs', import.meta.url), 'utf8');
source += '\nmodule.exports={appSearchPage,routerData,signedAppGet,appSearchParams,APP_SEARCH_API,APP_UA,DEVICE,SITE};';
const shimPath = join(shimDir, 'hongguo-verify.cjs');
writeFileSync(shimPath, source);
// hongguo.cjs 依赖宿主注入的全局 axios；此处用内置 fetch 模拟同一接口
globalThis.axios = async (url, options = {}) => {
    const res = await fetch(url, { headers: options.headers || {}, signal: AbortSignal.timeout(options.timeout || 20000) });
    return { status: res.status, data: await res.text() };
};
const hg = requireCjs(shimPath);

let failed = false;
function ok(name, cond, detail) {
    console.log(`${cond ? 'PASS' : 'FAIL'} | ${name} | ${detail}`);
    if (!cond) failed = true;
}
const idOf = (item) => String(item.series_id ?? item.seriesId ?? item.book_id ?? item.id ?? '');
const titleOf = (item) => String(item.title ?? item.book_name ?? item.name ?? '');
const overlap = (a, b) => a.filter((x) => { const id = idOf(x); return id && b.some((y) => idOf(y) === id); }).length;
const categoryRows = async (url) => {
    const data = await hg.routerData(url);
    const section = (data.loaderData || data)['category_$'] || data.category_page || {};
    return section.recommendList || [];
};

try {
    // A0. 探针：打印签名搜索接口的原始响应（用于诊断签名被拒原因）
    try {
        const probe = await hg.signedAppGet(hg.appSearchParams('总裁', 11, 20, {}, 1));
        console.log('A0 探针 code=', probe.code, 'message=', probe.message);
    } catch (e) {
        console.log('A0 探针异常 |', e.message);
    }
    // A1. 官网搜索路由（Kotlin search() 的 fallback 路径）：短剧站搜索应含漫剧/短剧混合结果
    const webSearch = async (keyword, page) => {
        const data = await hg.routerData(`${hg.SITE}/search/${encodeURIComponent(keyword)}?page=${page}`);
        const ld = data.loaderData || data;
        // 自适应：官网改版后 loaderData 下的键名可能变化，直接找含 searchList 的 section
        for (const [key, value] of Object.entries(ld)) {
            if (value && typeof value === 'object' && Array.isArray(value.searchList)) return value.searchList;
        }
        console.log('   搜索路由 loaderData keys:', Object.keys(ld).join(','));
        return [];
    };
    const s1 = await webSearch('修仙', 1);
    const s2 = await webSearch('修仙', 2);
    ok('A1 官网搜索第1页有结果', s1.length > 0, `count=${s1.length} 首条="${titleOf(s1[0] || {})}"`);
    ok('A2 官网搜索第2页非空且不重叠', s2.length > 0 && overlap(s1, s2) <= 2, `count=${s2.length} 重叠=${overlap(s1, s2)}`);

    // B. App 签名搜索（tab 11）：漫剧样本应能被短剧 tab 搜出
    try {
        const comic = await hg.appSearchPage('热门', 19, 1, 20);
        ok('B1 漫剧tab搜索可用', comic.list.length > 0, `count=${comic.list.length} hasMore=${comic.hasMore}`);
        const keyword = titleOf(comic.list[0] || {}).slice(0, 12);
        const viaShort = await hg.appSearchPage(keyword, 11, 1, 20);
        const titles = viaShort.list.map(titleOf);
        ok('B2 短剧tab(11)能搜出漫剧内容', viaShort.list.length > 0 && titles.some((t) => t.includes(keyword.slice(0, 4))),
            `样本="${keyword}" count=${viaShort.list.length} 首条="${titles[0] || ''}"`);
        const p1 = await hg.appSearchPage('总裁', 11, 1, 20);
        const p2 = await hg.appSearchPage('总裁', 11, 2, 20);
        ok('B3 短剧tab第1页有结果', p1.list.length > 0, `count=${p1.list.length} hasMore=${p1.hasMore}`);
        ok('B4 第2页有结果且与第1页不重复', p2.list.length > 0 && overlap(p1.list, p2.list) <= 2,
            `count=${p2.list.length} 重叠=${overlap(p1.list, p2.list)}`);
    } catch (e) {
        console.log('FAIL | B App签名搜索异常 |', e.message);
        failed = true;
    }

    // C. 官网分类翻页：每页 24 条，深翻页（第 6 页）与题材页（爱情）均可取且不重叠
    const c1 = await categoryRows(`${hg.SITE}/category/real-drama`);
    const c2 = await categoryRows(`${hg.SITE}/category/real-drama?page=2`);
    const c6 = await categoryRows(`${hg.SITE}/category/real-drama?page=6`);
    const r1 = await categoryRows(`${hg.SITE}/category/real-drama/romance`);
    const r2 = await categoryRows(`${hg.SITE}/category/real-drama/romance?page=2`);
    ok('C1 分类第1页 24 条', c1.length === 24, `count=${c1.length} 首条="${titleOf(c1[0] || {})}"`);
    ok('C2 第2页非空且不重叠', c2.length === 24 && overlap(c1, c2) === 0, `count=${c2.length} 重叠=${overlap(c1, c2)}`);
    ok('C3 第6页仍可取（超旧版5页限制）', c6.length === 24, `count=${c6.length} 与第1页重叠=${overlap(c1, c6)}`);
    ok('C4 爱情题材第1页 24 条', r1.length === 24, `count=${r1.length} 首条="${titleOf(r1[0] || {})}"`);
    ok('C5 爱情第2页非空且不重叠', r2.length === 24 && overlap(r1, r2) === 0, `count=${r2.length} 重叠=${overlap(r1, r2)}`);
} catch (e) {
    console.log('FAIL | 脚本异常 |', e.message);
    failed = true;
}
if (failed) process.exit(1);
console.log('全部验证通过');
