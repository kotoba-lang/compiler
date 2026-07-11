(ns kotoba.compiler.backend.native
  (:import [java.nio ByteBuffer ByteOrder]))

(defn- ->bytes [xs] (byte-array (map unchecked-byte xs)))

(defn- le64 [n]
  (let [b (doto (ByteBuffer/allocate 8) (.order ByteOrder/LITTLE_ENDIAN) (.putLong n))]
    (vec (.array b))))

(defn emit-x86-64 [value]
  ;; movabs rax, imm64; ret. The verifier accepts this exact grammar only.
  (->bytes (concat [0x48 0xb8] (le64 value) [0xc3])))

(defn- a64-movz [rd imm shift]
  (let [hw (quot shift 16)
        word (bit-or 0xd2800000 (bit-shift-left hw 21)
                     (bit-shift-left (bit-and imm 0xffff) 5) rd)]
    [(bit-and word 0xff) (bit-and (bit-shift-right word 8) 0xff)
     (bit-and (bit-shift-right word 16) 0xff) (bit-and (bit-shift-right word 24) 0xff)]))

(defn- a64-movk [rd imm shift]
  (let [hw (quot shift 16)
        word (bit-or 0xf2800000 (bit-shift-left hw 21)
                     (bit-shift-left (bit-and imm 0xffff) 5) rd)]
    [(bit-and word 0xff) (bit-and (bit-shift-right word 8) 0xff)
     (bit-and (bit-shift-right word 16) 0xff) (bit-and (bit-shift-right word 24) 0xff)]))

(defn emit-aarch64 [value]
  ;; movz/movk x0, four 16-bit lanes; ret. Constant shape simplifies admission.
  (->bytes (concat (a64-movz 0 value 0)
                 (a64-movk 0 (unsigned-bit-shift-right value 16) 16)
                 (a64-movk 0 (unsigned-bit-shift-right value 32) 32)
                 (a64-movk 0 (unsigned-bit-shift-right value 48) 48)
                 [0xc0 0x03 0x5f 0xd6])))
