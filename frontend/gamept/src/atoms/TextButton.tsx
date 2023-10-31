import { ITextButton } from "@/types/components/Button.types";

/**
 * 
 * @param props 
 * text => string, 버튼에 들어갈 문구
 * onClickEvent => () => void, 버튼 클릭 시 이벤트 
 * @returns 
 */

const TextButton = (props: ITextButton) => {
  return (
    <div className="text-[#FFA727] font-hol text-24">
      <a href="#" onClick={() => props.onClickEvent}>
        {props.text}
      </a>
    </div>
  );
}

export default TextButton;