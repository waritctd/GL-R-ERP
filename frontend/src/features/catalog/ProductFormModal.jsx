import { useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { FormField } from '../../components/common/FormField.jsx';

// UX-03: the only validation rule this form has ever had — price is required
// and must be > 0. Every other field is genuinely optional; do not add
// required-ness here without an explicit product decision, it would block
// legitimate saves (many catalog rows are missing grade/collection/etc).
const productFormSchema = z.object({
  productCode: z.string(),
  grade: z.string(),
  collection: z.string(),
  productName: z.string(),
  color: z.string(),
  surface: z.string(),
  sizeRaw: z.string(),
  price: z.string().refine((value) => value !== '' && Number(value) > 0, 'กรุณาใส่ราคา'),
  currency: z.string(),
  priceUnit: z.string(),
});

export function ProductFormModal({ product, factoryId, onClose, onSaved }) {
  const isEdit = !!product?.priceId;
  const [saving, setSaving] = useState(false);
  const [submitError, setSubmitError] = useState('');

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm({
    resolver: zodResolver(productFormSchema),
    defaultValues: {
      productCode: product?.productCode ?? '',
      grade:       product?.grade       ?? '',
      collection:  product?.collection  ?? '',
      productName: product?.productName ?? '',
      color:       product?.color       ?? '',
      surface:     product?.surface     ?? '',
      sizeRaw:     product?.sizeRaw     ?? '',
      price:       product?.price != null ? String(product.price) : '',
      currency:    product?.currency    ?? 'EUR',
      priceUnit:   product?.priceUnit   ?? 'per_sqm',
    },
    mode: 'onChange',
    reValidateMode: 'onChange',
  });

  async function submit(values) {
    setSubmitError('');
    // Payload shape is an API contract detail — preserved byte-identical to
    // the pre-migration hand-rolled version: every text field normalises
    // '' -> null, price becomes a Number, currency/priceUnit pass through.
    const payload = {
      productCode: values.productCode || null,
      grade:       values.grade       || null,
      collection:  values.collection  || null,
      productName: values.productName || null,
      color:       values.color       || null,
      surface:     values.surface     || null,
      sizeRaw:     values.sizeRaw     || null,
      price:       Number(values.price),
      currency:    values.currency,
      priceUnit:   values.priceUnit,
    };
    setSaving(true);
    try {
      if (isEdit) {
        await api.catalog.updateProduct(product.priceId, payload);
      } else {
        await api.catalog.addProduct({ ...payload, factoryId });
      }
      onSaved();
    } catch (err) {
      setSubmitError(err.message || 'บันทึกไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal
      title={isEdit ? 'แก้ไขสินค้า' : 'เพิ่มสินค้า'}
      onClose={onClose}
      footer={
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button type="submit" form="product-form" variant="primary" disabled={saving}>
            {saving ? 'กำลังบันทึก…' : 'บันทึก'}
          </Button>
        </>
      }
    >
      <form id="product-form" onSubmit={handleSubmit(submit)} className="space-y-3" noValidate>
        <div className="grid grid-cols-2 gap-3">
          <FormField label="รหัสสินค้า" htmlFor="pf-code">
            <input id="pf-code" type="text" {...register('productCode')} />
          </FormField>
          <FormField label="Grade" htmlFor="pf-grade">
            <input id="pf-grade" type="text" {...register('grade')} />
          </FormField>
        </div>
        <FormField label="Collection" htmlFor="pf-collection">
          <input id="pf-collection" type="text" {...register('collection')} />
        </FormField>
        <FormField label="ชื่อสินค้า" htmlFor="pf-name">
          <input id="pf-name" type="text" {...register('productName')} />
        </FormField>
        <div className="grid grid-cols-2 gap-3">
          <FormField label="สี" htmlFor="pf-color">
            <input id="pf-color" type="text" {...register('color')} />
          </FormField>
          <FormField label="ผิว" htmlFor="pf-surface">
            <input id="pf-surface" type="text" {...register('surface')} />
          </FormField>
        </div>
        <FormField label="ขนาด" htmlFor="pf-size">
          <input
            id="pf-size"
            type="text"
            placeholder="เช่น 60x60, 30x90"
            {...register('sizeRaw')}
          />
        </FormField>
        <div className="grid grid-cols-3 gap-3">
          <FormField label="ราคา" htmlFor="pf-price" error={errors.price?.message} required>
            <input
              id="pf-price"
              type="number"
              step="0.01"
              min="0"
              {...register('price')}
            />
          </FormField>
          <FormField label="สกุลเงิน" htmlFor="pf-currency">
            <select id="pf-currency" {...register('currency')}>
              <option>EUR</option>
              <option>USD</option>
              <option>THB</option>
              <option>GBP</option>
            </select>
          </FormField>
          <FormField label="หน่วย" htmlFor="pf-unit">
            <select id="pf-unit" {...register('priceUnit')}>
              <option value="per_sqm">ม²</option>
              <option value="per_piece">แผ่น</option>
              <option value="per_box">กล่อง</option>
              <option value="per_linear_m">ม.</option>
            </select>
          </FormField>
        </div>
        {submitError && <p className="text-sm text-red-600" role="alert">{submitError}</p>}
      </form>
    </Modal>
  );
}
