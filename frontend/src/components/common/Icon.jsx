import React from 'react';
import Badge from 'lucide-react/dist/esm/icons/badge.mjs';
import BadgeDollarSign from 'lucide-react/dist/esm/icons/badge-dollar-sign.mjs';
import Bell from 'lucide-react/dist/esm/icons/bell.mjs';
import FileText from 'lucide-react/dist/esm/icons/file-text.mjs';
import BadgeCheck from 'lucide-react/dist/esm/icons/badge-check.mjs';
import BriefcaseBusiness from 'lucide-react/dist/esm/icons/briefcase-business.mjs';
import Building2 from 'lucide-react/dist/esm/icons/building-2.mjs';
import CalendarClock from 'lucide-react/dist/esm/icons/calendar-clock.mjs';
import Check from 'lucide-react/dist/esm/icons/check.mjs';
import ChevronDown from 'lucide-react/dist/esm/icons/chevron-down.mjs';
import ChevronLeft from 'lucide-react/dist/esm/icons/chevron-left.mjs';
import ChevronRight from 'lucide-react/dist/esm/icons/chevron-right.mjs';
import ChevronUp from 'lucide-react/dist/esm/icons/chevron-up.mjs';
import Circle from 'lucide-react/dist/esm/icons/circle.mjs';
import CircleUserRound from 'lucide-react/dist/esm/icons/circle-user-round.mjs';
import ClipboardCheck from 'lucide-react/dist/esm/icons/clipboard-check.mjs';
import Clock3 from 'lucide-react/dist/esm/icons/clock-3.mjs';
import Home from 'lucide-react/dist/esm/icons/home.mjs';
import Info from 'lucide-react/dist/esm/icons/info.mjs';
import LayoutDashboard from 'lucide-react/dist/esm/icons/layout-dashboard.mjs';
import LockKeyhole from 'lucide-react/dist/esm/icons/lock-keyhole.mjs';
import LogOut from 'lucide-react/dist/esm/icons/log-out.mjs';
import Mail from 'lucide-react/dist/esm/icons/mail.mjs';
import Menu from 'lucide-react/dist/esm/icons/menu.mjs';
import Pencil from 'lucide-react/dist/esm/icons/pencil.mjs';
import Phone from 'lucide-react/dist/esm/icons/phone.mjs';
import Plus from 'lucide-react/dist/esm/icons/plus.mjs';
import RefreshCcw from 'lucide-react/dist/esm/icons/refresh-ccw.mjs';
import Search from 'lucide-react/dist/esm/icons/search.mjs';
import ShieldCheck from 'lucide-react/dist/esm/icons/shield-check.mjs';
import SlidersHorizontal from 'lucide-react/dist/esm/icons/sliders-horizontal.mjs';
import TriangleAlert from 'lucide-react/dist/esm/icons/triangle-alert.mjs';
import Upload from 'lucide-react/dist/esm/icons/upload.mjs';
import Paperclip from 'lucide-react/dist/esm/icons/paperclip.mjs';
import Calculator from 'lucide-react/dist/esm/icons/calculator.mjs';
import UserCog from 'lucide-react/dist/esm/icons/user-cog.mjs';
import UserPlus from 'lucide-react/dist/esm/icons/user-plus.mjs';
import Users from 'lucide-react/dist/esm/icons/users.mjs';
import X from 'lucide-react/dist/esm/icons/x.mjs';

const icons = {
  badge: Badge,
  badgeDollar: BadgeDollarSign,
  bell: Bell,
  fileText: FileText,
  badgeCheck: BadgeCheck,
  briefcase: BriefcaseBusiness,
  building: Building2,
  calendar: CalendarClock,
  check: Check,
  chevronDown: ChevronDown,
  chevronLeft: ChevronLeft,
  chevronRight: ChevronRight,
  chevronUp: ChevronUp,
  clipboard: ClipboardCheck,
  clock: Clock3,
  close: X,
  dashboard: LayoutDashboard,
  home: Home,
  info: Info,
  lock: LockKeyhole,
  logout: LogOut,
  mail: Mail,
  menu: Menu,
  pencil: Pencil,
  phone: Phone,
  plus: Plus,
  refresh: RefreshCcw,
  search: Search,
  setting: SlidersHorizontal,
  shield: ShieldCheck,
  triangleAlert: TriangleAlert,
  upload: Upload,
  paperclip: Paperclip,
  calculator: Calculator,
  user: CircleUserRound,
  userCog: UserCog,
  userPlus: UserPlus,
  users: Users,
};

export function Icon({ name, size = 18, strokeWidth = 2, ...props }) {
  const Component = icons[name] ?? Circle;
  return <Component aria-hidden="true" focusable="false" size={size} strokeWidth={strokeWidth} {...props} />;
}
