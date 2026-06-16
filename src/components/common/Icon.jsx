import Badge from 'lucide-react/dist/esm/icons/badge.js';
import BadgeCheck from 'lucide-react/dist/esm/icons/badge-check.js';
import BriefcaseBusiness from 'lucide-react/dist/esm/icons/briefcase-business.js';
import Building2 from 'lucide-react/dist/esm/icons/building-2.js';
import CalendarClock from 'lucide-react/dist/esm/icons/calendar-clock.js';
import Check from 'lucide-react/dist/esm/icons/check.js';
import ChevronLeft from 'lucide-react/dist/esm/icons/chevron-left.js';
import ChevronRight from 'lucide-react/dist/esm/icons/chevron-right.js';
import Circle from 'lucide-react/dist/esm/icons/circle.js';
import CircleUserRound from 'lucide-react/dist/esm/icons/circle-user-round.js';
import ClipboardCheck from 'lucide-react/dist/esm/icons/clipboard-check.js';
import Clock3 from 'lucide-react/dist/esm/icons/clock-3.js';
import Home from 'lucide-react/dist/esm/icons/home.js';
import LayoutDashboard from 'lucide-react/dist/esm/icons/layout-dashboard.js';
import LockKeyhole from 'lucide-react/dist/esm/icons/lock-keyhole.js';
import LogOut from 'lucide-react/dist/esm/icons/log-out.js';
import Mail from 'lucide-react/dist/esm/icons/mail.js';
import Pencil from 'lucide-react/dist/esm/icons/pencil.js';
import Phone from 'lucide-react/dist/esm/icons/phone.js';
import Plus from 'lucide-react/dist/esm/icons/plus.js';
import RefreshCcw from 'lucide-react/dist/esm/icons/refresh-ccw.js';
import Search from 'lucide-react/dist/esm/icons/search.js';
import ShieldCheck from 'lucide-react/dist/esm/icons/shield-check.js';
import UserCog from 'lucide-react/dist/esm/icons/user-cog.js';
import UserPlus from 'lucide-react/dist/esm/icons/user-plus.js';
import Users from 'lucide-react/dist/esm/icons/users.js';
import X from 'lucide-react/dist/esm/icons/x.js';

const icons = {
  badge: Badge,
  badgeCheck: BadgeCheck,
  briefcase: BriefcaseBusiness,
  building: Building2,
  calendar: CalendarClock,
  check: Check,
  chevronLeft: ChevronLeft,
  chevronRight: ChevronRight,
  clipboard: ClipboardCheck,
  clock: Clock3,
  close: X,
  dashboard: LayoutDashboard,
  home: Home,
  lock: LockKeyhole,
  logout: LogOut,
  mail: Mail,
  pencil: Pencil,
  phone: Phone,
  plus: Plus,
  refresh: RefreshCcw,
  search: Search,
  shield: ShieldCheck,
  user: CircleUserRound,
  userCog: UserCog,
  userPlus: UserPlus,
  users: Users,
};

export function Icon({ name, size = 18, strokeWidth = 2, ...props }) {
  const Component = icons[name] ?? Circle;
  return <Component aria-hidden="true" focusable="false" size={size} strokeWidth={strokeWidth} {...props} />;
}
