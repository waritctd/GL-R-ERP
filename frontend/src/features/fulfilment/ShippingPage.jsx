import { useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { canReadOutbound, hasPermission } from '../../app/permissions.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack } from '../../components/common/Layout.jsx';
import { cn } from '../../utils/cn.js';
import { MyOrdersPage } from '../myOrders/MyOrdersPage.jsx';
import { WarehouseHome } from '../warehouse/WarehouseHome.jsx';
import { ImportFulfilmentPage } from './ImportFulfilmentPage.jsx';
import { ShippingSwitch } from './ShippingSwitch.jsx';

const READ_ONLY_NOTE = {
  in: 'ขาเข้าดูได้อย่างเดียว ฝ่ายนำเข้าเป็นผู้จัดรอบและเลื่อนขั้นตอน',
  out: 'ขาออกดูได้อย่างเดียว ฝ่ายคลังสินค้าเป็นผู้ตรวจเอกสารและจัดรอบส่ง',
  salesOut: 'ขาออกดูได้อย่างเดียว ขอส่งของได้ที่แท็บ ส่งของ ของงานนั้น',
};

/**
 * การขนส่ง, one page with two legs: ขาเข้า (factory to warehouse, import's rounds) and ขาออก (warehouse to customer, the
 * warehouse manager's home). Who may act on which leg is the backend's (import writes the rounds, the warehouse planner writes
 * the trips); the other side reads. `sales` (/my-orders): the sales team's ขาเข้า is its own list (MyOrdersPage), ขาออก the
 * same read-only queue. A person who may not read ขาออก (canReadOutbound) gets their one leg as before, with no rail.
 */
export function ShippingPage({ user, showToast, sales = false }) {
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const planner = Boolean(user.canPlanDeliveries);
  const twoLegs = canReadOutbound(user);
  const requested = params.get('leg');
  const leg = twoLegs && (requested === 'in' || requested === 'out') ? requested : (planner && !sales ? 'out' : 'in');

  // The same keys the legs use, so the rail's counts and the leg below share one request each.
  const inKey = sales ? queryKeys.myIncoming() : queryKeys.shipmentRounds();
  const rounds = useQuery({ queryKey: queryKeys.shipmentRounds(), queryFn: () => api.shipmentRounds.list(), enabled: twoLegs && !sales });
  const incoming = useQuery({ queryKey: queryKeys.myIncoming(), queryFn: () => api.myIncoming.get(), enabled: twoLegs && sales, retry: false });
  const home = useQuery({ queryKey: queryKeys.deliveryTripsHome(), queryFn: () => api.deliveryTrips.home(), enabled: twoLegs });

  if (!twoLegs) return sales ? <MyOrdersPage user={user} showToast={showToast} /> : <ImportFulfilmentPage user={user} showToast={showToast} />;

  const readOnly = { in: !sales && !hasPermission(user.role, 'canActOnFulfilment'), out: !planner };
  const inCount = sales
    ? (incoming.isError ? '—' : incoming.data ? `${incoming.data.rows.length} รายการ` : '…')
    : (rounds.isError ? '—' : rounds.data ? `${rounds.data.rounds.length} รอบ · รอจัดรอบ ${rounds.data.waiting.length}` : '…');
  const meta = {
    in: { title: 'ขาเข้า', sub: 'โรงงาน → โกดัง · ฝ่ายนำเข้า', count: inCount },
    out: { title: 'ขาออก', sub: 'โกดัง → ลูกค้า · ฝ่ายคลังสินค้า', count: home.isError ? '—' : home.data ? `รอตรวจ ${home.data.toCheck.length} · รอจัดรอบส่ง ${home.data.waiting.length}` : '…' },
  };

  return (
    <PageStack>
      <ShippingSwitch user={user} current={sales ? 'orders' : 'rounds'} />
      <PageHeader
        title="การขนส่ง"
        subtitle="ของเข้าโกดังและของออกถึงลูกค้า อยู่ในหน้าเดียว"
        actions={(
          <Button type="button" variant="icon" title="รีเฟรช" aria-label="รีเฟรช"
            onClick={() => Promise.all([queryClient.invalidateQueries({ queryKey: inKey }), queryClient.invalidateQueries({ queryKey: queryKeys.deliveryTripsHome() })])}>
            <Icon name="refresh" />
          </Button>
        )}
      />
      <div role="group" aria-label="ขาของการขนส่ง" className="grid grid-cols-2 gap-2 min-[1041px]:grid-cols-[6fr_4fr] min-[1041px]:gap-0 min-[1041px]:overflow-hidden min-[1041px]:rounded-md min-[1041px]:border min-[1041px]:border-border">
        {['in', 'out'].map((key) => (
          <button
            key={key}
            type="button"
            aria-pressed={leg === key}
            data-testid={`leg-${key}`}
            onClick={() => setParams({ leg: key }, { replace: true })}
            className={cn(
              'flex min-h-16 cursor-pointer flex-col items-start justify-center gap-0.5 rounded-md border px-3.5 py-2 text-left min-[1041px]:min-h-[3.25rem] min-[1041px]:rounded-none min-[1041px]:border-0',
              leg === key ? 'border-primary bg-primary text-surface' : 'border-border bg-surface text-text hover:bg-surface-hover',
            )}
          >
            <b className="text-base">{meta[key].title}<span className="ml-2 text-xs font-normal opacity-80">{meta[key].sub}</span></b>
            <small className="text-xs opacity-80">{meta[key].count}</small>
          </button>
        ))}
      </div>
      {readOnly[leg] ? (
        <p className="m-0 rounded-md border border-border bg-surface-subtle px-3 py-2 text-sm text-text-secondary" data-testid="leg-readonly-note">{READ_ONLY_NOTE[sales && leg === 'out' ? 'salesOut' : leg]}</p>
      ) : null}
      {leg === 'out' ? <WarehouseHome embedded readOnly={readOnly.out} />
        : sales ? <MyOrdersPage user={user} showToast={showToast} embedded />
          // Only the warehouse gets the stripped rounds view; readers keep the page they had.
          : <ImportFulfilmentPage user={user} showToast={showToast} embedded readOnly={readOnly.in && planner} />}
    </PageStack>
  );
}
